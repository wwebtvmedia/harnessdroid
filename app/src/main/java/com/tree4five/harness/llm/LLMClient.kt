package com.tree4five.harness.llm

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

class LLMClient(private val context: Context) {
    private var llmService: ILLMService? = null
    private var isBound = false
    private val TAG = "LLMClient"

    /**
     * Secures a connection to the local LLMProvider AIDL service.
     * Uses suspendCancellableCoroutine to convert the callback-based IPC binding into a suspend function.
     */
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
                continuation.resume(llmService!!)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Disconnected from LLMProvider")
                llmService = null
                isBound = false
            }
        }

        val intent = Intent("com.tree4five.gguf.ACTION_LLM_SERVICE").apply {
            setPackage("com.tree4five.gguf") // Security: Explicitly target the LLM Provider app to prevent intent hijacking
        }
        
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            continuation.resumeWithException(SecurityException("Unable to bind to LLMProvider service. Ensure the app is installed and permissions are granted."))
        }

        continuation.invokeOnCancellation {
            if (isBound) {
                context.unbindService(connection)
                isBound = false
                llmService = null
            }
        }
    }

    /**
     * Executes a prompt securely and iteratively waits for the complete response.
     * For embedded constraints, we avoid high-frequency UI updates and just return the final string.
     */
    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        val service = getService()
        suspendCancellableCoroutine { continuation ->
            val callback = object : ILLMCallback.Stub() {
                override fun onTokenReceived(token: String) {
                    // Intentionally empty for embedded systems to avoid IPC overhead / CPU wake locks on every single token.
                    // We only care about the final JSON output for tool calling.
                }
                
                override fun onGenerationComplete(fullText: String) {
                    continuation.resume(fullText)
                }
            }
            try {
                service.generateTextStream(prompt, callback)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }
}
