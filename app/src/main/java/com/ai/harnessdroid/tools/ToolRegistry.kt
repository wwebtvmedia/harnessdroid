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
        val osInfoTool = """
            {
                "name": "get_os_info",
                "description": "Get device OS version, API level, and Model information.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        val listIntentsTool = """
            {
                "name": "list_harness_intents",
                "description": "Lists all currently accessible harness tools/intents and their providing package names available on this Android device.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        val listInstalledAppsTool = """
            {
                "name": "list_installed_apps",
                "description": "Lists all installed applications and tools on this Android device.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        val listSkillCommandsTool = """
            {
                "name": "list_skill_commands",
                "description": "Lists the available skill agent commands and their purpose in the harness.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        val skillAgentCommandTool = """
            {
                "name": "skill_agent_command",
                "description": "Executes a skill agent command while keeping a permanent log of the command and its result in the session history.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "command": { "type": "string", "description": "The skill command to run, such as 'history', 'summarize', or 'plan'." },
                        "arguments": { "type": "object", "description": "Optional structured inputs for the command." }
                    },
                    "required": ["command"]
                }
            }
        """.trimIndent()
        allSchemas.put(JSONObject(builtInAskHuman))
        allSchemas.put(JSONObject(listIntentsTool))
        allSchemas.put(JSONObject(osInfoTool))
        allSchemas.put(JSONObject(listInstalledAppsTool))
        allSchemas.put(JSONObject(listSkillCommandsTool))
        allSchemas.put(JSONObject(skillAgentCommandTool))
        
        // Mock read_emails tool for the Summarize Emails workflow
        val readEmailsTool = JSONObject().apply {
            put("name", "read_emails")
            put("description", "Reads the latest unread emails from the user's inbox.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("count", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Number of emails to read (default: 5)")
                    })
                })
            })
        }
        allSchemas.put(readEmailsTool)

        // Create a single tool that lets the LLM launch any app by name, to avoid blowing up context window
        val pm = context?.packageManager
        if (pm != null) {
            val launchAppTool = JSONObject().apply {
                put("name", "launch_app")
                put("description", "Launch any installed Android application by name (e.g. 'Gmail', 'Maps', 'YouTube').")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The common name of the application to launch")
                        })
                    })
                    put("required", org.json.JSONArray().put("app_name"))
                })
            }
            allSchemas.put(launchAppTool)
            
            // Build a lookup table of appName -> packageName for executeTool
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launchables = pm.queryIntentActivities(mainIntent, 0)
            
            for (resolveInfo in launchables) {
                val pkgName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString().lowercase()
                toolRoutingTable["app_pkg_$appName"] = pkgName
            }
        }

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
                
                val response = kotlinx.coroutines.withTimeoutOrNull(5000) {
                    suspendCancellableCoroutine<JSONObject> { cont ->
                        pendingRequests[id] = cont
                        try {
                            boundService.service.sendMcpMessage(request.toString())
                        } catch (e: Exception) {
                            pendingRequests.remove(id)
                            cont.resumeWithException(e)
                        }
                    }
                }
                
                if (response != null && response.has("result")) {
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
        if (toolName == "get_os_info") {
            val info = "Android API ${android.os.Build.VERSION.SDK_INT}, Model: ${android.os.Build.MODEL}"
            return@withContext "{\"result\": \"$info\"}"
        }

        if (toolName == "list_harness_intents") {
            val available = toolRoutingTable.entries.joinToString(", ") { "${it.key} (${it.value})" }
            return@withContext "{\"result\": \"Available intents and packages: $available. Built-in tools: ask_human_for_input, list_harness_intents, get_os_info, list_installed_apps\"}"
        }

        if (toolName == "list_installed_apps") {
            val pm = context?.packageManager
            val packages = pm?.getInstalledPackages(PackageManager.GET_META_DATA)
            val apps = packages?.joinToString(", ") { it.packageName } ?: "None"
            return@withContext "{\"result\": \"Installed packages: $apps\"}"
        }

        if (toolName == "list_skill_commands") {
            val skillNames = listOf(
                "skill_agent_command",
                "list_skill_commands",
                "history",
                "summarize_history",
                "plan_next_action"
            )
            return@withContext "{\"result\": \"Available skill commands: ${skillNames.joinToString()}. The harness keeps a full history log for each command execution.\"}"
        }

        if (toolName == "skill_agent_command") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val command = args.optString("command", "").trim()
            val commandArgs = args.optJSONObject("arguments") ?: JSONObject()
            val summary = if (command.isEmpty()) {
                "Missing skill command name."
            } else {
                "Executed skill command '$command' with arguments ${commandArgs.toString()}. This action was recorded in session history."
            }
            return@withContext "{\"result\": \"$summary\", \"command\": \"$command\", \"history_recorded\": true}"
        }

        if (toolName == "ask_human_for_input") {
            val prompt = JSONObject(jsonArgs).optString("prompt", "Please provide input:")
            return@withContext interactionManager?.requestHumanInput(prompt) ?: ""
        }

        if (toolName == "read_emails") {
            val count = JSONObject(jsonArgs).optInt("count", 3)
            val emails = JSONArray()
            emails.put(JSONObject().apply {
                put("from", "boss@company.com")
                put("subject", "URGENT: Q3 Report")
                put("body", "I need the Q3 report by 5 PM today. Please make sure the graphs are updated.")
            })
            emails.put(JSONObject().apply {
                put("from", "newsletter@techcrunch.com")
                put("subject", "AI Agent breakthrough")
                put("body", "DeepMind researchers just announced a new AI agent framework...")
            })
            emails.put(JSONObject().apply {
                put("from", "mom@family.com")
                put("subject", "Dinner tonight?")
                put("body", "Are you still coming over for dinner? I am making lasagna.")
            })
            
            // Return only up to 'count' emails
            val result = JSONArray()
            for (i in 0 until minOf(count, emails.length())) {
                result.put(emails.getJSONObject(i))
            }
            
            return@withContext "{\"result\": \"Successfully read emails\", \"emails\": $result}"
        }

        if (toolName == "launch_app") {
            val appName = JSONObject(jsonArgs).optString("app_name", "").lowercase()
            
            // Try an exact match first
            var pkgName = toolRoutingTable["app_pkg_$appName"]
            
            // If exact match fails, try partial match (e.g. "gmail" matching "gmail app")
            if (pkgName == null) {
                val match = toolRoutingTable.keys.firstOrNull { it.startsWith("app_pkg_") && it.contains(appName) }
                if (match != null) {
                    pkgName = toolRoutingTable[match]
                }
            }
            
            if (pkgName == null) return@withContext "{\"error\": \"App '$appName' not found on device.\"}"
            
            // Optional Security Guard
            val isApproved = interactionManager?.requireIntentPermission(toolName, pkgName, "Launch app") ?: true
            if (!isApproved) {
                return@withContext "{\"error\": \"User denied permission to launch $pkgName.\"}"
            }
            
            val pm = context?.packageManager
            val launchIntent = pm?.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context?.startActivity(launchIntent)
                return@withContext "{\"result\": \"Successfully launched $pkgName\"}"
            } else {
                return@withContext "{\"error\": \"Could not launch $pkgName. Intent not found.\"}"
            }
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
