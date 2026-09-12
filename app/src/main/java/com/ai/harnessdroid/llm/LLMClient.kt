package com.ai.harnessdroid.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.ai.harnessdroid.memory.EmbeddingCodec
import com.tree4five.gguf.IEmbedCallback
import com.tree4five.gguf.ILLMCallback
import com.tree4five.gguf.ILLMService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class LLMClient(private val context: Context?) {
    private var llmService: ILLMService? = null
    private var isBound = false
    private val TAG = "LLMClient"

    /**
     * Binder oneway calls only preserve order from the SAME calling thread,
     * so the whole begin/set/generate sequence runs on a single-threaded
     * dispatcher: chunks can never arrive out of order on the provider side.
     */
    private val uploadDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Cached embedding dimension; null = never probed, -1 = unavailable. */
    @Volatile
    private var cachedEmbeddingDim: Int? = null

    internal fun resolveCustomUrl(rawUrl: String, apiType: String = "OpenAI"): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed

        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//", ignoreCase = true) -> "http:$trimmed"
            else -> "http://$trimmed"
        }

        return try {
            val uri = java.net.URI(normalized)
            val hasPath = !uri.path.isNullOrBlank()
            if (hasPath) {
                normalized
            } else if (apiType.equals("OpenAI", ignoreCase = true)) {
                "$normalized/v1/chat/completions"
            } else {
                normalized
            }
        } catch (_: Exception) {
            normalized
        }
    }

    private suspend fun getService(): ILLMService = suspendCancellableCoroutine { continuation ->
        if (llmService != null) {
            continuation.resume(llmService!!)
            return@suspendCancellableCoroutine
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "Connected to LLMProvider")
                llmService = ILLMService.Stub.asInterface(service)
                isBound = true
                if (continuation.isActive) {
                    continuation.resume(llmService!!)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Disconnected from LLMProvider")
                llmService = null
                isBound = false
            }
        }

        val intent = Intent("com.tree4five.gguf.ACTION_LLM_SERVICE").apply {
            setPackage("com.tree4five.gguf")
        }
        
        val bound = context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (bound != true) {
            continuation.resumeWithException(SecurityException("Unable to bind to LLMProvider service."))
        }

        continuation.invokeOnCancellation {
            if (isBound) {
                context?.unbindService(connection)
                isBound = false
                llmService = null
            }
        }
    }

    open suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        val config = context?.let { LLMConfigManager(it) }
        if (config != null && !config.useTree4Five) {
            // Use Custom HTTP LLM Provider
            try {
                val normalizedUrl = resolveCustomUrl(config.customUrl, config.customApiType)
                if (normalizedUrl.isBlank()) {
                    return@withContext "Error: Custom LLM URL is empty"
                }

                val url = java.net.URL(normalizedUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                // Explicit timeouts: the JDK defaults are infinite, so a stalled provider
                // would hang the AgentLoop forever.
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                if (config.customApiKey.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer ${config.customApiKey}")
                }
                connection.doOutput = true

                // OpenAI-compatible chat payload; the model name is user-configurable.
                val payload = org.json.JSONObject().apply {
                    put("model", config.customModel)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                connection.outputStream.use { os ->
                    val input = payload.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = org.json.JSONObject(response)
                    return@withContext jsonResponse.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "") ?: response
                } else {
                    return@withContext "Error: Custom LLM API returned HTTP $responseCode"
                }
            } catch (e: Exception) {
                return@withContext "Error connecting to Custom LLM: ${e.message}"
            }
        } else {
            // Use local Tree4Five Service
            val service = getService()
            suspendCancellableCoroutine { continuation ->
                val callback = object : ILLMCallback.Stub() {
                    override fun onTokenReceived(token: String) {}
                    override fun onGenerationComplete(fullText: String) {
                        if (continuation.isActive) {
                            continuation.resume(fullText)
                        }
                    }
                }
                try {
                    service.generateTextStream(prompt, callback)
                    continuation.invokeOnCancellation {
                        val keepAlive = callback
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    /**
     * Dimension of the local model's embedding space, or -1 when embeddings
     * are unavailable (remote HTTP LLM, old provider without the transaction,
     * model without a usable token_embd). Callers fall back to text mode.
     */
    open suspend fun embeddingDim(): Int {
        cachedEmbeddingDim?.let { return it }
        val config = context?.let { LLMConfigManager(it) }
        if (config != null && !config.useTree4Five) {
            cachedEmbeddingDim = -1
            return -1
        }
        return try {
            val service = getService()
            val dim = service.embeddingDim
            val resolved = if (dim > 0) dim else -1
            cachedEmbeddingDim = resolved
            resolved
        } catch (t: Throwable) {
            // Older provider build (unknown binder transaction), dead service...
            Log.w(TAG, "embeddingDim probe failed: ${t.message}")
            cachedEmbeddingDim = -1
            -1
        }
    }

    /**
     * Embeds `text` into the model's space (mean of token-embedding rows,
     * no forward pass). Returns null when embeddings are unavailable; the
     * caller falls back to text.
     */
    open suspend fun embedText(text: String): FloatArray? {
        if (embeddingDim() <= 0) return null
        return try {
            val service = getService()
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : IEmbedCallback.Stub() {
                        override fun onEmbedding(q8: ByteArray?, dim: Int) {
                            if (q8 == null || dim <= 0) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            try {
                                val vector = EmbeddingCodec.decodeFlat(q8, dim)
                                if (continuation.isActive) continuation.resume(vector)
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }

                        override fun onError(message: String?) {
                            Log.w(TAG, "embedText error: $message")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    try {
                        service.embedText(text, callback)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "embedText failed: ${t.message}")
            null
        }
    }

    /**
     * Generation from injected embeddings (latent context) with the actual
     * question as a text followup. Returns null when the provider cannot do
     * it (embeddings unavailable, upload or generation failed); the caller
     * must then fall back to [generateText].
     */
    open suspend fun generateFromEmbeddings(
        vectors: FloatArray,
        count: Int? = null,
        followupPrompt: String,
        nPredict: Int = 256,
        temperature: Float = 0f
    ): String? {
        if (embeddingDim() <= 0) return null
        val dim = cachedEmbeddingDim ?: return null
        val resolvedCount = count ?: vectors.size / dim
        if (resolvedCount <= 0 || vectors.size < resolvedCount * dim) return null
        return try {
            val service = getService()
            withContext(uploadDispatcher) {
                val handle = service.beginEmbeddingInput(resolvedCount, dim)
                if (handle <= 0) return@withContext null

                try {
                    // Chunks stay far below the 512 KB Binder transaction limit:
                    // 32 vectors x (4 + dim) bytes (~29 KB at dim=896).
                    val chunkVectors = maxOf(1, 32_000 / (4 + dim))
                    var start = 0
                    while (start < resolvedCount) {
                        val chunk = minOf(chunkVectors, resolvedCount - start)
                        val bytes = ByteArray(chunk * (4 + dim))
                        val buf = java.nio.ByteBuffer.wrap(bytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (t in 0 until chunk) {
                            val base = (start + t) * dim
                            val slice = java.util.Arrays.copyOfRange(vectors, base, base + dim)
                            val packed = EmbeddingCodec.encodeOne(slice)
                            buf.put(packed)
                        }
                        service.setEmbeddingChunk(handle, start, bytes)
                        start += chunk
                    }

                    val result = suspendCancellableCoroutine { continuation ->
                        val callback = object : ILLMCallback.Stub() {
                            override fun onTokenReceived(token: String) {}
                            override fun onGenerationComplete(fullText: String) {
                                if (continuation.isActive) continuation.resume(fullText)
                            }
                        }
                        try {
                            service.generateFromEmbeddings(
                                handle, resolvedCount, followupPrompt, nPredict, temperature, callback
                            )
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                    // The provider prefixes load errors; treat them as failures
                    // so the AgentLoop falls back to the text path.
                    if (result.startsWith("Error:")) null else result
                } finally {
                    try {
                        service.releaseEmbeddings(handle)
                    } catch (_: Exception) {
                        // Slot is LRU-evicted anyway.
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "generateFromEmbeddings failed: ${t.message}")
            null
        }
    }
}
