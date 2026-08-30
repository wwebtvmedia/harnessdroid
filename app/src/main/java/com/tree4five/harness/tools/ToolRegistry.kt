package com.tree4five.harness.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.tree4five.harness.IToolCallback
import com.tree4five.harness.IToolProviderService
import com.tree4five.harness.core.InteractionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BoundToolService(
    val componentName: ComponentName,
    val service: IToolProviderService,
    val connection: ServiceConnection
)

class ToolRegistry(
    private val context: Context,
    private val interactionManager: InteractionManager
) {
    private val TAG = "ToolRegistry"
    private val boundServices = mutableMapOf<String, BoundToolService>()
    private val toolRoutingTable = mutableMapOf<String, String>() // Maps toolName -> packageName

    /**
     * Discovers all apps that expose the harness tool AIDL interface.
     */
    suspend fun discoverAndBindTools(): String = withContext(Dispatchers.IO) {
        val intent = Intent("com.tree4five.harness.ACTION_PROVIDE_TOOLS")
        
        // Requires <queries> in AndroidManifest.xml to work on API 30+
        val resolveInfos = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
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
        allSchemas.put(org.json.JSONObject(builtInAskHuman))

        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.serviceInfo.packageName
            val className = resolveInfo.serviceInfo.name
            val component = ComponentName(packageName, className)

            try {
                val boundService = bindService(component)
                boundServices[packageName] = boundService
                
                val schemasJsonStr = boundService.service.availableTools
                val schemasArray = JSONArray(schemasJsonStr)
                
                for (i in 0 until schemasArray.length()) {
                    val tool = schemasArray.getJSONObject(i)
                    val toolName = tool.getString("name")
                    toolRoutingTable[toolName] = packageName
                    allSchemas.put(tool)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind or parse tools from $packageName", e)
            }
        }
        
        return@withContext allSchemas.toString(2)
    }

    private suspend fun bindService(componentName: ComponentName): BoundToolService = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                val binder = IToolProviderService.Stub.asInterface(service)
                continuation.resume(BoundToolService(name, binder, this))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                boundServices.remove(name.packageName)
            }
        }

        val intent = Intent().apply { component = componentName }
        val success = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!success) {
            continuation.resumeWithException(SecurityException("Could not bind to tool service: ${componentName.flattenToString()}"))
        }
    }

    /**
     * Executes a tool asynchronously over AIDL and waits for the callback result.
     * Incorporates human-in-the-loop Guard checks before firing external intents.
     */
    suspend fun executeTool(toolName: String, jsonArgs: String): String = withContext(Dispatchers.IO) {
        // Handle built-in tools first
        if (toolName == "ask_human_for_input") {
            val prompt = org.json.JSONObject(jsonArgs).optString("prompt", "User input required:")
            return@withContext interactionManager.requestHumanInput(prompt)
        }

        val packageName = toolRoutingTable[toolName] 
            ?: return@withContext "{\"error\": \"Tool $toolName not found in registry\"}"
            
        val boundService = boundServices[packageName] 
            ?: return@withContext "{\"error\": \"Service $packageName disconnected\"}"

        // Security Guard: Hand over control to the human to approve this intent
        // In a real app, you could have a whitelist/blacklist of safe tools to avoid prompting every time.
        val isApproved = interactionManager.requireIntentPermission(toolName, packageName, jsonArgs)
        if (!isApproved) {
            return@withContext "{\"error\": \"User denied permission to execute this tool.\"}"
        }

        suspendCancellableCoroutine { continuation ->
            val callback = object : IToolCallback.Stub() {
                override fun onToolSuccess(jsonResult: String) {
                    continuation.resume(jsonResult)
                }

                override fun onToolError(errorMessage: String) {
                    continuation.resume("{\"error\": \"$errorMessage\"}")
                }
            }

            try {
                boundService.service.executeTool(toolName, jsonArgs, callback)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    fun unbindAll() {
        boundServices.values.forEach { 
            context.unbindService(it.connection) 
        }
        boundServices.clear()
        toolRoutingTable.clear()
    }
}
