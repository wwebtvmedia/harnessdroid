package com.ai.harnessdroid.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.ai.harnessdroid.IToolCallback
import com.ai.harnessdroid.IToolProviderService
import com.ai.harnessdroid.core.InteractionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BoundToolService(
    val componentName: ComponentName,
    val service: IToolProviderService,
    val connection: ServiceConnection,
    val callback: IToolCallback.Stub
)

open class ToolRegistry(
    private val context: Context?,
    private val interactionManager: InteractionManager?
) {
    private val TAG = "ToolRegistry"
    private val boundServices = mutableMapOf<String, BoundToolService>()
    private val toolRoutingTable = mutableMapOf<String, String>() // Maps toolName -> packageName
    
    private val mcpRequestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, Continuation<JSONObject>>()

    /**
     * Discovers all apps that expose the harness tool AIDL interface.
     */
    open suspend fun discoverAndBindTools(): String = withContext(Dispatchers.IO) {
        val intent = Intent("com.ai.harnessdroid.ACTION_PROVIDE_TOOLS")
        
        // Requires <queries> in AndroidManifest.xml to work on API 30+
        val resolveInfos = context?.packageManager?.queryIntentServices(intent, PackageManager.GET_META_DATA)
        val allSchemas = JSONArray()

        // We can also inject the built-in Human-in-the-loop tool here
        val builtInAskHuman = """
            {
                "name": "ask_human_for_input",
                "description": "Hand over control to the human to ask for information or clarification.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "prompt": { "type": "string", "description": "The question to ask the user" }
                    },
                    "required": ["prompt"]
                }
            }
        """.trimIndent()
        val listIntentsTool = """
            {
                "name": "list_harness_intents",
                "description": "Lists all currently accessible harness tools/intents available on this Android device.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        allSchemas.put(JSONObject(builtInAskHuman))
        allSchemas.put(JSONObject(listIntentsTool))

        for (resolveInfo in resolveInfos ?: emptyList()) {
            val packageName = resolveInfo.serviceInfo.packageName
            val className = resolveInfo.serviceInfo.name
            val component = ComponentName(packageName, className)

            try {
                val boundService = bindService(component)
                boundServices[packageName] = boundService
                
                // Send MCP tools/list request
                val id = mcpRequestId.getAndIncrement()
                val request = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", "tools/list")
                }
                
                val response = suspendCancellableCoroutine<JSONObject> { cont ->
                    pendingRequests[id] = cont
                    try {
                        boundService.service.sendMcpMessage(request.toString())
                    } catch (e: Exception) {
                        pendingRequests.remove(id)
                        cont.resumeWithException(e)
                    }
                }
                
                if (response.has("result")) {
                    val resultObj = response.getJSONObject("result")
                    if (resultObj.has("tools")) {
                        val toolsArray = resultObj.getJSONArray("tools")
                        for (i in 0 until toolsArray.length()) {
                            val tool = toolsArray.getJSONObject(i)
                            
                            val schema = JSONObject()
                            schema.put("name", tool.getString("name"))
                            schema.put("description", tool.optString("description", ""))
                            if (tool.has("inputSchema")) {
                                schema.put("parameters", tool.getJSONObject("inputSchema"))
                            } else if (tool.has("parameters")) {
                                schema.put("parameters", tool.getJSONObject("parameters"))
                            }
                            
                            toolRoutingTable[tool.getString("name")] = packageName
                            allSchemas.put(schema)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind or parse tools from $packageName", e)
            }
        }
        
        return@withContext allSchemas.toString(2)
    }

    private suspend fun bindService(componentName: ComponentName): BoundToolService = suspendCancellableCoroutine { continuation ->
        var serviceBinder: IToolProviderService? = null
        
        val mcpCallback = object : IToolCallback.Stub() {
            override fun onMcpMessage(jsonRpcMessage: String) {
                try {
                    val response = JSONObject(jsonRpcMessage)
                    val id = response.optInt("id", -1)
                    if (id != -1) {
                        pendingRequests.remove(id)?.resume(response)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MCP response", e)
                }
            }
        }
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                serviceBinder = IToolProviderService.Stub.asInterface(service)
                try {
                    serviceBinder?.registerCallback(mcpCallback)
                    continuation.resume(BoundToolService(name, serviceBinder!!, this, mcpCallback))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                boundServices.remove(name.packageName)
            }
        }

        val intent = Intent().apply { component = componentName }
        val success = context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (success != true) {
            continuation.resumeWithException(SecurityException("Could not bind to tool service: ${componentName.flattenToString()}"))
        }
    }

    /**
     * Executes a tool asynchronously over AIDL and waits for the callback result.
     * Incorporates human-in-the-loop Guard checks before firing external intents.
     */
    open suspend fun executeTool(toolName: String, jsonArgs: String): String = withContext(Dispatchers.IO) {
        // Handle built-in tools first
        if (toolName == "list_harness_intents") {
            val available = toolRoutingTable.keys.joinToString(", ")
            return@withContext "{"result": "Available intents: $available, ask_human_for_input, list_harness_intents"}"
        }

        if (toolName == "ask_human_for_input") {
            val prompt = JSONObject(jsonArgs).optString("prompt", "User input required:")
            return@withContext interactionManager?.requestHumanInput(prompt) ?: ""
        }

        val packageName = toolRoutingTable[toolName] 
            ?: return@withContext "{\"error\": \"Tool $toolName not found in registry\"}"
            
        val boundService = boundServices[packageName] 
            ?: return@withContext "{\"error\": \"Service $packageName disconnected\"}"

        // Security Guard: Hand over control to the human to approve this intent
        val isApproved = interactionManager?.requireIntentPermission(toolName, packageName, jsonArgs) ?: true
        if (!isApproved) {
            return@withContext "{\"error\": \"User denied permission to execute this tool.\"}"
        }

        val id = mcpRequestId.getAndIncrement()
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", JSONObject(jsonArgs))
            })
        }

        return@withContext suspendCancellableCoroutine { continuation ->
            pendingRequests[id] = object : Continuation<JSONObject> {
                override val context = continuation.context
                override fun resumeWith(result: Result<JSONObject>) {
                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        if (response?.has("error") == true) {
                            val error = response.getJSONObject("error").optString("message", "Unknown error")
                            continuation.resume("{\"error\": \"$error\"}")
                        } else if (response?.has("result") == true) {
                            val toolResult = response.getJSONObject("result")
                            if (toolResult.has("content")) {
                                val contentArray = toolResult.getJSONArray("content")
                                if (contentArray.length() > 0) {
                                    val text = contentArray.getJSONObject(0).optString("text", "")
                                    continuation.resume(text)
                                    return
                                }
                            }
                            continuation.resume(toolResult.toString())
                        } else {
                            continuation.resume("{\"error\": \"Invalid response format\"}")
                        }
                    } else {
                        continuation.resumeWithException(result.exceptionOrNull() ?: Exception("Unknown error"))
                    }
                }
            }
            
            try {
                boundService.service.sendMcpMessage(request.toString())
            } catch (e: Exception) {
                pendingRequests.remove(id)
                continuation.resumeWithException(e)
            }
        }
    }

    fun unbindAll() {
        boundServices.values.forEach { 
            try {
                it.service.unregisterCallback(it.callback)
            } catch (e: Exception) {}
            context?.unbindService(it.connection) 
        }
        boundServices.clear()
        toolRoutingTable.clear()
    }
}
