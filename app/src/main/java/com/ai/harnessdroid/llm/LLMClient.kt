package com.ai.harnessdroid.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
                val url = java.net.URL(config.customUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                if (config.customApiKey.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer ${config.customApiKey}")
                }
                connection.doOutput = true

                // Simple JSON payload structure, could vary by API Type
                val payload = org.json.JSONObject().apply {
                    put("model", "custom-model")
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
}
