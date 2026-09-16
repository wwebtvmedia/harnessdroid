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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    /** Provider mode the cached dimension was probed under (true = remote HTTP). */
    @Volatile
    private var cachedDimForRemoteMode: Boolean = false

    internal fun resolveCustomUrl(rawUrl: String, apiType: String = "OpenAI"): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        // A trailing slash means "server root", not an explicit endpoint: strip it so
        // the OpenAI-compatible suffix below is appended (POSTing to "/" yields 404).
        while (trimmed.endsWith("/") && !trimmed.endsWith("://")) {
            trimmed = trimmed.dropLast(1)
        }

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

    /** Snapshot of the remote HTTP settings; non-null when the user selected Custom LLM. */
    internal data class CustomLLMSettings(
        val url: String,
        val apiKey: String,
        val apiType: String,
        val model: String
    )

    internal fun customSettings(config: LLMConfigManager): CustomLLMSettings? =
        if (config.useTree4Five) null
        else CustomLLMSettings(config.customUrl, config.customApiKey, config.customApiType, config.customModel)

    open suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        val settings = context?.let { LLMConfigManager(it) }?.let { customSettings(it) }
        generateWithSettings(prompt, settings)
    }

    /** Context window (tokens) when the remote value is unknown; safe lower bound. */
    private val DEFAULT_CONTEXT_TOKENS = 2048
    /** Ollama's num_ctx default when the Modelfile does not set one explicitly. */
    private val OLLAMA_DEFAULT_NUM_CTX = 4096
    /** LLMProvider builds older than v1.1.4 expose no getContextLength: 2048 is
     *  the N_CTX constant their engine was built with. */
    private val LOCAL_FALLBACK_CONTEXT_TOKENS = 2048

    @Volatile
    private var cachedRemoteContextTokens: Int? = null

    /**
     * Best-effort context window (in tokens) of the ACTIVE provider, so callers
     * can size their prompts to the model instead of a constant. Remote: query
     * Ollama's /api/show (an explicit num_ctx wins; otherwise Ollama's runtime
     * default capped by the model's GGUF training context). Local: the AIDL
     * getContextLength() that LLMProvider reads from the loaded GGUF. Never throws.
     */
    suspend fun getContextTokens(): Int = withContext(Dispatchers.IO) {
        val settings = context?.let { LLMConfigManager(it) }?.let { customSettings(it) }
        if (settings != null) {
            cachedRemoteContextTokens
                ?: queryOllamaContextTokens(settings).also { cachedRemoteContextTokens = it }
                ?: DEFAULT_CONTEXT_TOKENS
        } else {
            try {
                getService().getContextLength().takeIf { it > 0 }
            } catch (_: Exception) {
                // Older provider build: the transaction is unknown to the Binder.
                null
            } ?: LOCAL_FALLBACK_CONTEXT_TOKENS
        }
    }

    private fun queryOllamaContextTokens(settings: CustomLLMSettings): Int? {
        val base = baseUrlOf(settings.url) ?: return null
        return try {
            val conn = (java.net.URL("$base/api/show").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("""{"model":"${settings.model}"}""".toByteArray()) }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val json = org.json.JSONObject(body)
            val explicit = Regex("num_ctx\\s+(\\d+)").find(json.optString("parameters"))
                ?.groupValues?.get(1)?.toIntOrNull()
            val train = json.optJSONObject("model_info")?.let { mi ->
                mi.keys().asSequence()
                    .filter { it.endsWith(".context_length") }
                    .map { mi.optInt(it) }
                    .firstOrNull { it > 0 }
            }
            (explicit ?: train?.let { minOf(it, OLLAMA_DEFAULT_NUM_CTX) } ?: OLLAMA_DEFAULT_NUM_CTX)
                .takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun baseUrlOf(rawUrl: String): String? {
        return try {
            var t = rawUrl.trim()
            if (t.isEmpty()) return null
            while (t.endsWith("/") && !t.endsWith("://")) t = t.dropLast(1)
            if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) {
                t = "http://$t"
            }
            val uri = java.net.URI(t)
            val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            "${uri.scheme}://${uri.host}:$port"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates from `prompt`, using the remote HTTP LLM when `settings` is non-null.
     *
     * When the remote endpoint fails (unreachable, HTTP error, blank URL) the call
     * FALLS BACK to the local Tree4Five LLMProvider so the agent stays usable;
     * the remote error text is only surfaced when the local provider fails too.
     */
    internal open suspend fun generateWithSettings(prompt: String, settings: CustomLLMSettings?): String {
        if (settings != null) {
            val customResult = try {
                generateViaCustom(settings, prompt)
            } catch (e: Exception) {
                "Error connecting to Custom LLM: ${e.message}"
            }
            if (!customResult.startsWith("Error")) {
                return customResult
            }
            Log.w(TAG, "Custom LLM unusable (${customResult}); falling back to the local LLMProvider")
            return try {
                generateViaLocal(prompt)
            } catch (_: Exception) {
                // Both paths failed: surface the remote error, it is the one the user configured.
                customResult
            }
        }
        return generateViaLocal(prompt)
    }

    /** One POST to the user-configured OpenAI-compatible endpoint. Error strings mark failures. */
    internal open suspend fun generateViaCustom(settings: CustomLLMSettings, prompt: String): String {
        val normalizedUrl = resolveCustomUrl(settings.url, settings.apiType)
        if (normalizedUrl.isBlank()) {
            return "Error: Custom LLM URL is empty"
        }

        val url = java.net.URL(normalizedUrl)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        // Explicit timeouts: the JDK defaults are infinite, so a stalled provider
        // would hang the AgentLoop forever. 240s of read window lets mid-size
        // "thinking" models finish; anything slower fails cleanly into the
        // (also bounded) local fallback instead of stalling.
        connection.connectTimeout = 15_000
        connection.readTimeout = 240_000
        if (settings.apiKey.isNotEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        connection.doOutput = true

        // OpenAI-compatible chat payload; the model name is user-configurable.
        // temperature=0: the FSM makes tool-picking decisions, sampling randomness
        // there (Ollama defaults to 0.8) makes tiny models skip steps at random.
        val payload = org.json.JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0)
            // Cap runaway generations: a tiny model that has already derailed will
            // otherwise ramble for thousands of tokens (mixed-language word salad).
            put("max_tokens", 600)
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
            return jsonResponse.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: response
        }
        return "Error: Custom LLM API returned HTTP $responseCode"
    }

    /** Bound on one local-provider generation: without it a wedged provider
     *  (model still loading, OOM, dead service) hangs the fallback path — and
     *  therefore the agent loop — forever. */
    private val LOCAL_GENERATION_TIMEOUT_MS = 180_000L

    /** Generation through the local Tree4Five binder service. */
    internal open suspend fun generateViaLocal(prompt: String): String {
        val service = getService()
        return try {
            withTimeout(LOCAL_GENERATION_TIMEOUT_MS) {
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
                            // Keep the callback referenced so the provider's
                            // binder thread never dereferences a collected peer.
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Re-throw as a plain failure: callers (generateWithSettings, tests)
            // must treat it like any other provider error, not a coroutine teardown.
            throw java.util.concurrent.TimeoutException(
                "Local LLMProvider did not answer within ${LOCAL_GENERATION_TIMEOUT_MS / 1000}s"
            )
        }
    }

    /**
     * Dimension of the local model's embedding space, or -1 when embeddings
     * are unavailable (remote HTTP LLM, old provider without the transaction,
     * model without a usable token_embd). Callers fall back to text mode.
     */
    open suspend fun embeddingDim(): Int {
        val config = context?.let { LLMConfigManager(it) }
        val remoteMode = config?.useTree4Five == false
        // The dimension only holds for the provider mode it was probed in: switching
        // between the local provider and a remote HTTP LLM must force a re-probe.
        cachedEmbeddingDim?.let { if (remoteMode == cachedDimForRemoteMode) return it }
        if (remoteMode) {
            cachedEmbeddingDim = -1
            cachedDimForRemoteMode = true
            return -1
        }
        return try {
            val service = getService()
            val dim = service.embeddingDim
            val resolved = if (dim > 0) dim else -1
            cachedEmbeddingDim = resolved
            cachedDimForRemoteMode = remoteMode
            resolved
        } catch (t: Throwable) {
            // Older provider build (unknown binder transaction), dead service...
            Log.w(TAG, "embeddingDim probe failed: ${t.message}")
            cachedEmbeddingDim = -1
            cachedDimForRemoteMode = remoteMode
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
