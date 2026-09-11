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
import kotlinx.coroutines.withTimeoutOrNull
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

    // Timeouts so a misbehaving tool provider can never stall the AgentLoop forever.
    private val BIND_TIMEOUT_MS = 5_000L
    private val TOOL_CALL_TIMEOUT_MS = 15_000L

    private val mcpRequestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, Continuation<JSONObject>>()

    open fun discoveryIntentActions(): List<String> = listOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        Intent.ACTION_SENDTO,
        Intent.ACTION_DIAL,
        Intent.ACTION_CALL,
        Intent.ACTION_MAIN,
        Intent.ACTION_ASSIST,
        Intent.ACTION_PROCESS_TEXT,
        Intent.ACTION_GET_CONTENT,
        Intent.ACTION_EDIT,
        Intent.ACTION_PICK,
        Intent.ACTION_WEB_SEARCH,
        "com.ai.harnessdroid.ACTION_PROVIDE_TOOLS"
    )

    /**
     * Discovers all apps that expose the harness tool AIDL interface.
     * Standard Android intents are preferred; legacy custom-action discovery is kept as a fallback.
     * This keeps the lookup small and sequential to minimize memory pressure while still surfacing
     * the Android apps that register compatible intent filters.
     */
    open suspend fun discoverAndBindTools(): String = withContext(Dispatchers.IO) {
        val allIntentCandidates = mutableListOf<Intent>()
        for (action in discoveryIntentActions()) {
            when (action) {
                Intent.ACTION_MAIN -> allIntentCandidates += Intent(action).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_SENDTO,
                Intent.ACTION_WEB_SEARCH, Intent.ACTION_GET_CONTENT, Intent.ACTION_EDIT, Intent.ACTION_PICK,
                Intent.ACTION_PROCESS_TEXT, Intent.ACTION_ASSIST, Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                    allIntentCandidates += Intent(action).apply { addCategory(Intent.CATEGORY_DEFAULT) }
                    allIntentCandidates += Intent(action).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                }
                else -> allIntentCandidates += Intent(action).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            }
        }

        val resolveInfos = mutableListOf<android.content.pm.ResolveInfo>()
        val seenServices = mutableSetOf<String>()
        val pm = context?.packageManager

        for (intent in allIntentCandidates) {
            val queried = pm?.queryIntentServices(intent, PackageManager.GET_META_DATA) ?: emptyList()
            for (resolveInfo in queried) {
                val serviceInfo = resolveInfo.serviceInfo ?: continue
                val packageName = serviceInfo.packageName ?: continue
                val key = "$packageName/${serviceInfo.name}"
                if (key in seenServices) continue
                if (packageName == context?.packageName) continue
                if (!isUserInstalledApp(packageName)) continue
                if (!serviceInfo.exported) continue
                resolveInfos.add(resolveInfo)
                seenServices.add(key)
            }
        }

        val allSchemas = JSONArray()

        // We can also inject the built-in Human-in-the-loop tool here
        val builtInAskHuman = """
            {
                "name": "ask_human_for_input",
                "description": "Hand over control to the human to ask for information or clarification. Supports an optional default answer and a 2-minute timeout.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "prompt": { "type": "string", "description": "The question to ask the user" },
                        "default_answer": { "type": "string", "description": "Optional fallback value if the human does not answer within two minutes." },
                        "timeout_seconds": { "type": "integer", "description": "Optional timeout in seconds; defaults to 120." }
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
        val listCompatibleIntentAppsTool = """
            {
                "name": "list_compatible_intent_apps",
                "description": "Finds installed apps that match a task's required Android intent capabilities while keeping the query narrow and memory-efficient.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "capabilities": { "type": "array", "items": { "type": "string" }, "description": "Short capability names such as read_mail, send_email, view_web, or open_app." }
                    }
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
        allSchemas.put(JSONObject(listCompatibleIntentAppsTool))
        allSchemas.put(JSONObject(listSkillCommandsTool))
        allSchemas.put(JSONObject(skillAgentCommandTool))
        
        // Removed mock read_emails tool. Real tools will be discovered via Intent.

        // Create a single tool that lets the LLM launch any app by name, to avoid blowing up context window
        val packageManager = context?.packageManager
        if (packageManager != null) {
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
            
            val sendIntentTool = JSONObject().apply {
                put("name", "send_android_intent")
                put("description", "Send a standard Android intent to launch an action. E.g. view a URL, send an email, dial a number.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("description", "The intent action, e.g. 'android.intent.action.VIEW', 'android.intent.action.SENDTO'")
                        })
                        put("data_uri", JSONObject().apply {
                            put("type", "string")
                            put("description", "The data URI, e.g. 'mailto:person@example.com', 'tel:1234567890', 'http://example.com'")
                        })
                    })
                    put("required", org.json.JSONArray().put("action"))
                })
            }
            allSchemas.put(sendIntentTool)
            
            // Build a lookup table of appName -> packageName for executeTool
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launchables = packageManager.queryIntentActivities(mainIntent, 0)
            
            for (resolveInfo in launchables) {
                val pkgName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(packageManager).toString().lowercase()
                toolRoutingTable["app_pkg_$appName"] = pkgName
            }
        }

        for (resolveInfo in resolveInfos ?: emptyList()) {
            val packageName = resolveInfo.serviceInfo.packageName
            val className = resolveInfo.serviceInfo.name
            val component = ComponentName(packageName, className)

            try {
                // Reuse an already-bound connection: discoverAndBindTools runs on every task
                // (and on the "List Tools" button), and re-binding would leak binder connections.
                val boundService = boundServices[packageName]
                    ?: withTimeoutOrNull(BIND_TIMEOUT_MS) { bindService(component) }
                    ?: throw java.util.concurrent.TimeoutException("Timed out binding to $packageName")
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
                        pendingRequests.remove(id)?.let { cont ->
                            try { cont.resume(response) } catch (_: IllegalStateException) {
                                // Continuation already resumed or cancelled (e.g. timeout) — ignore late reply.
                            }
                        }
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
                    if (continuation.isActive) {
                        continuation.resume(BoundToolService(name, serviceBinder!!, this, mcpCallback))
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
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
            return@withContext JSONObject().put("result", info).toString()
        }

        if (toolName == "list_harness_intents") {
            val available = toolRoutingTable.entries.joinToString(", ") { "${it.key} (${it.value})" }
            val result = "Available intents and packages: $available. Built-in tools: ask_human_for_input, list_harness_intents, get_os_info, list_installed_apps"
            return@withContext JSONObject().put("result", result).toString()
        }

        if (toolName == "list_installed_apps") {
            val pm = context?.packageManager
            val packages = pm?.getInstalledPackages(PackageManager.GET_META_DATA)
            val apps = packages?.joinToString(", ") { it.packageName } ?: "None"
            return@withContext JSONObject().put("result", "Installed packages: $apps").toString()
        }

        if (toolName == "list_compatible_intent_apps") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val capabilityHints = args.optJSONArray("capabilities")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx, "").trim().ifBlank { null } }
            } ?: emptyList()
            val matches = discoverCompatibleIntentApps(capabilityHints)
            val summary = if (matches.isEmpty()) "No compatible Android intent-filter apps found for the requested capability." else "Compatible apps: ${matches.joinToString(", ")}"
            return@withContext JSONObject().put("result", summary).toString()
        }

        if (toolName == "list_skill_commands") {
            val skillNames = listOf(
                "skill_agent_command",
                "list_skill_commands",
                "history",
                "summarize_history",
                "plan_next_action"
            )
            val result = "Available skill commands: ${skillNames.joinToString()}. The harness keeps a full history log for each command execution."
            return@withContext JSONObject().put("result", result).toString()
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
            return@withContext JSONObject()
                .put("result", summary)
                .put("command", command)
                .put("history_recorded", true)
                .toString()
        }

        if (toolName == "ask_human_for_input") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val prompt = args.optString("prompt", "Please provide input:")
            val defaultAnswer = args.optString("default_answer", "").ifBlank { null }
            val timeoutSeconds = args.optLong("timeout_seconds", 120L).coerceAtLeast(1L)
            return@withContext interactionManager?.requestHumanInput(prompt, defaultAnswer, timeoutSeconds) ?: ""
        }



        if (toolName == "launch_app") {
            val appNameRaw = JSONObject(jsonArgs).optString("app_name", "")
            val appName = appNameRaw.lowercase().trim()
            val normalized = normalizeAppName(appName)

            val pm = context?.packageManager
            var pkgName: String? = null

            // 1. Exact label match (e.g. "gmail" -> "app_pkg_gmail")
            pkgName = toolRoutingTable["app_pkg_$appName"]

            // 2. Partial label match (e.g. "gmail" matching "gmail app")
            if (pkgName == null && appName.isNotEmpty()) {
                val match = toolRoutingTable.keys.firstOrNull { it.startsWith("app_pkg_") && it.contains(appName) }
                pkgName = match?.let { toolRoutingTable[it] }
            }

            // 3. Package-name match (e.g. "com.google.android.gm" or a fragment of it)
            if (pkgName == null && appName.isNotEmpty()) {
                val match = toolRoutingTable.entries.firstOrNull { (key, value) ->
                    val pkg = value.lowercase()
                    key.startsWith("app_pkg_") && (pkg.contains(appName) || appName.contains(pkg))
                }?.value
                pkgName = match
            }

            // 4. Normalized match ignoring spaces/dashes/underscores (e.g. "play store" -> "playstore")
            if (pkgName == null && normalized.isNotEmpty()) {
                val match = toolRoutingTable.entries.firstOrNull { (key, value) ->
                    key.startsWith("app_pkg_") && (
                        normalizeAppName(key.removePrefix("app_pkg_")) == normalized ||
                            normalizeAppName(value) == normalized ||
                            normalizeAppName(value).contains(normalized) ||
                            normalized.contains(normalizeAppName(value))
                        )
                }?.value
                pkgName = match
            }

            // 5. Last resort: treat the raw input as a package name and ask the OS directly
            if (pkgName == null && appNameRaw.isNotBlank()) {
                val direct = try { pm?.getLaunchIntentForPackage(appNameRaw.trim()) } catch (_: Exception) { null }
                if (direct != null) pkgName = appNameRaw.trim()
            }

            if (pkgName == null) {
                // Return the available app list so a small LLM can self-correct on the next turn.
                val available = toolRoutingTable.keys
                    .filter { it.startsWith("app_pkg_") }
                    .map { it.removePrefix("app_pkg_") }
                    .take(30)
                    .joinToString(", ")
                return@withContext JSONObject()
                    .put("error", "App '$appNameRaw' not found on device.")
                    .put("available_apps", available)
                    .put("hint", "Retry launch_app with an app_name from available_apps, or use send_android_intent.")
                    .toString()
            }

            // Optional Security Guard
            val isApproved = interactionManager?.requireIntentPermission(toolName, pkgName, "Launch app") ?: true
            if (!isApproved) {
                return@withContext JSONObject().put("error", "User denied permission to launch $pkgName.").toString()
            }

            val launchIntent = pm?.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context?.startActivity(launchIntent)
                return@withContext JSONObject().put("result", "Successfully launched $pkgName").toString()
            } else {
                return@withContext JSONObject().put("error", "Could not launch $pkgName. Intent not found.").toString()
            }
        }

        val packageName = toolRoutingTable[toolName]
            ?: return@withContext JSONObject().put("error", "Tool $toolName not found in registry").toString()

        val boundService = boundServices[packageName]
            ?: return@withContext JSONObject().put("error", "Service $packageName disconnected").toString()

        // Security Guard: Hand over control to the human to approve this intent
        val isApproved = interactionManager?.requireIntentPermission(toolName, packageName, jsonArgs) ?: true
        if (!isApproved) {
            return@withContext JSONObject().put("error", "User denied permission to execute this tool.").toString()
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

        val response = withTimeoutOrNull(TOOL_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { continuation ->
                pendingRequests[id] = object : Continuation<JSONObject> {
                    override val context = continuation.context
                    override fun resumeWith(result: Result<JSONObject>) {
                        if (result.isSuccess) {
                            val response = result.getOrNull()
                            if (response?.has("error") == true) {
                                val error = response.getJSONObject("error").optString("message", "Unknown error")
                                continuation.resume(JSONObject().put("error", error).toString())
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
                                continuation.resume(JSONObject().put("error", "Invalid response format").toString())
                            }
                        } else {
                            continuation.resumeWithException(result.exceptionOrNull() ?: Exception("Unknown error"))
                        }
                    }
                }

                continuation.invokeOnCancellation { pendingRequests.remove(id) }

                try {
                    boundService.service.sendMcpMessage(request.toString())
                } catch (e: Exception) {
                    pendingRequests.remove(id)
                    continuation.resumeWithException(e)
                }
            }
        }

        return@withContext response
            ?: JSONObject().put("error", "Tool $toolName timed out after ${TOOL_CALL_TIMEOUT_MS}ms").toString()
    }

    fun unbindAll() {
        boundServices.values.forEach {
            try {
                it.service.unregisterCallback(it.callback)
            } catch (e: Exception) {}
            try {
                context?.unbindService(it.connection)
            } catch (e: Exception) {
                // Service may already be unbound (e.g. after onServiceDisconnected).
            }
        }
        boundServices.clear()
        toolRoutingTable.clear()
    }

    /**
     * Maps a free-form capability hint (e.g. "read_mail", "send_email", "view_web")
     * to the concrete Android intent actions/data that apps must handle to provide it.
     * Capability names never appear inside app labels, so substring matching cannot work;
     * resolving actual intents is the reliable way to find capable apps.
     */
    private fun capabilityToQueryIntents(capability: String): List<Intent> {
        val c = normalizeAppName(capability)
        return when {
            c.contains("mail") || c.contains("email") -> listOf(
                Intent(Intent.ACTION_SENDTO).apply { data = android.net.Uri.parse("mailto:") },
                Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
                Intent(Intent.ACTION_VIEW).apply { data = android.net.Uri.parse("mailto:") }
            )
            c.contains("web") || c.contains("browser") || c.contains("url") || c.contains("http") -> listOf(
                Intent(Intent.ACTION_VIEW).apply { data = android.net.Uri.parse("http://www.example.com") },
                Intent(Intent.ACTION_WEB_SEARCH)
            )
            c.contains("dial") || c.contains("call") || c.contains("phone") -> listOf(
                Intent(Intent.ACTION_DIAL).apply { data = android.net.Uri.parse("tel:") }
            )
            c.contains("search") || c.contains("find") -> listOf(
                Intent(Intent.ACTION_WEB_SEARCH),
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            )
            c.contains("openapp") || c.contains("launchapp") || c.contains("startapp") || c.contains("app") -> listOf(
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            )
            c.contains("share") || c.contains("send") -> listOf(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*" }
            )
            c.contains("image") || c.contains("photo") || c.contains("picture") || c.contains("pick") -> listOf(
                Intent(Intent.ACTION_PICK).apply { type = "image/*" },
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            )
            c.contains("text") || c.contains("process") || c.contains("edit") -> listOf(
                Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain" },
                Intent(Intent.ACTION_EDIT).apply { type = "text/plain" }
            )
            c.contains("assist") -> listOf(
                Intent(Intent.ACTION_ASSIST)
            )
            else -> emptyList()
        }
    }

    private fun discoverCompatibleIntentApps(capabilityHints: List<String>): List<String> {
        val pm = context?.packageManager ?: return emptyList()
        val matches = linkedSetOf<String>()

        // Resolve the concrete intents implied by the requested capabilities.
        val queries = capabilityHints.flatMap { capabilityToQueryIntents(it) }

        // No (or unknown) hints: fall back to the launcher grid so the caller still gets a useful list.
        val effectiveQueries = queries.ifEmpty {
            listOf(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }

        for (intent in effectiveQueries) {
            val resolveInfos = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (_: Exception) {
                emptyList()
            }
            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo?.packageName ?: continue
                if (pkgName == context?.packageName) continue
                val title = try { resolveInfo.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
                if (title.isNotEmpty()) matches += "$title ($pkgName)" else matches += pkgName
            }
        }

        return matches.toList().take(20)
    }

    /** Lowercases and strips separators so "Play Store", "play_store" and "playstore" all compare equal. */
    private fun normalizeAppName(name: String): String =
        name.lowercase().trim().replace(Regex("[\\s_\\-]"), "")

    private fun isUserInstalledApp(packageName: String): Boolean {
        val pm = context?.packageManager ?: return false
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            !isSystem
        } catch (_: Exception) {
            false
        }
    }

    // Expose a small helper so consumers (like AgentLoop) can request human input
    open suspend fun requestHumanInput(prompt: String, defaultAnswer: String? = null, timeoutSeconds: Long = 120): String? {
        return interactionManager?.requestHumanInput(prompt, defaultAnswer, timeoutSeconds)
    }
}
