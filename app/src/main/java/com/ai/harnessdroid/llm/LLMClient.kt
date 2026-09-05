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
        val service = getService()
        suspendCancellableCoroutine { continuation ->
            // Strongly reference the callback so it isn't garbage collected!
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
                
                // Keep the strong reference alive for the duration of the suspend call
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
