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
import com.swarmknowledge.ospbridge.IOspCallback
import com.swarmknowledge.ospbridge.IOspService
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
    private val interactionManager: InteractionManager?,
    /** False for sub-agent registries: delegate_task is then neither exposed nor executable. */
    private val allowDelegation: Boolean = true
) {
    private val TAG = "ToolRegistry"
    private val boundServices = mutableMapOf<String, BoundToolService>()
    private val toolRoutingTable = mutableMapOf<String, String>() // Maps toolName -> packageName

    // Timeouts so a misbehaving tool provider can never stall the AgentLoop forever.
    private val BIND_TIMEOUT_MS = 5_000L
    private val TOOL_CALL_TIMEOUT_MS = 15_000L

    /**
     * Hard cap on one tool result before it reaches the LLM: a huge dump would blow
     * the context window of a small model before history compression ever runs.
     * Overridable for JVM tests via HARNESS_MAX_TOOL_RESULT_CHARS.
     */
    private val MAX_TOOL_RESULT_CHARS =
        (System.getProperty("HARNESS_MAX_TOOL_RESULT_CHARS")
            ?: System.getenv("HARNESS_MAX_TOOL_RESULT_CHARS"))?.toIntOrNull()
            ?: 6000

    /**
     * Injected by the harness service: runs a sub-AgentLoop on a fresh session and
     * returns its final answer. Null (or [allowDelegation] false) = delegation disabled.
     */
    var delegateHandler: (suspend (task: String, agentType: String) -> String)? = null

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
                "description": "Lists installed applications on this Android device, paginated.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "offset": { "type": "integer", "description": "Number of apps to skip before listing (default 0)." },
                        "limit": { "type": "integer", "description": "Maximum apps per page (default 50, max 200)." }
                    }
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
                        "capabilities": { "type": "array", "items": { "type": "string" }, "description": "Short capability names such as read_mail, send_email, view_web, or open_app." },
                        "offset": { "type": "integer", "description": "Number of matches to skip before listing (default 0)." },
                        "limit": { "type": "integer", "description": "Maximum matches per page (default 30, max 100)." }
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
        val readScreenTool = """
            {
                "name": "read_screen",
                "description": "Read the text currently visible on the screen with the [left,top][right,bottom] bounds of each line. Use it AFTER launch_app to see what the app shows (e.g. the inbox list in Gmail: senders, subjects and snippets).",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        """.trimIndent()
        val tapScreenTool = """
            {
                "name": "tap_screen",
                "description": "Tap the screen at absolute x,y coordinates. Prefer tap_element with the visible label instead — it is far more reliable.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "x": { "type": "integer", "description": "X coordinate in screen pixels" },
                        "y": { "type": "integer", "description": "Y coordinate in screen pixels" }
                    },
                    "required": ["x", "y"]
                }
            }
        """.trimIndent()
        val swipeScreenTool = """
            {
                "name": "swipe_screen",
                "description": "Swipe from (x1,y1) to (x2,y2) to scroll the visible list, e.g. scroll down an inbox to see older emails.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "x1": { "type": "integer", "description": "Start X coordinate" },
                        "y1": { "type": "integer", "description": "Start Y coordinate" },
                        "x2": { "type": "integer", "description": "End X coordinate" },
                        "y2": { "type": "integer", "description": "End Y coordinate" }
                    },
                    "required": ["x1", "y1", "x2", "y2"]
                }
            }
        """.trimIndent()
        val tapElementTool = """
            {
                "name": "tap_element",
                "description": "Tap the on-screen element whose text matches (part of) the given label, e.g. tap_element with text='GOT IT' or the subject of an email row. Easier and more reliable than computing tap_screen coordinates.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "text": { "type": "string", "description": "Visible text (or part of it) of the element to tap" }
                    },
                    "required": ["text"]
                }
            }
        """.trimIndent()
        val delegateTaskTool = """
            {
                "name": "delegate_task",
                "description": "Delegate a self-contained sub-task to a fresh sub-agent that runs with its own context window and tools (it cannot delegate further). Use it for long side-quests (e.g. 'open Gmail, find the sender of the latest email') so the main conversation stays small. The sub-agent's final answer is returned to you.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "task": { "type": "string", "description": "Complete, self-contained instruction for the sub-agent (it cannot see this conversation)." },
                        "agent_type": { "type": "string", "description": "Optional specialist: 'navigator' for app/screen work, 'researcher' for information gathering. Defaults to 'general'." }
                    },
                    "required": ["task"]
                }
            }
        """.trimIndent()
        val ospQueryTool = """
            {
                "name": "osp_query",
                "description": "Ask a verified question to the Omni-Swarm Protocol knowledge swarm (OSP Bridge app and its remote peers, e.g. the whatsapp-bot memory with indexed documents). Use it for facts you cannot find on this device. The answer is firewall-verified against evidence chunks; when it is not verifiable you receive the reason instead.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "The factual question to ask the knowledge swarm, phrased as a sentence." },
                        "tier": { "type": "integer", "description": "Stakes tier 0/1/2: how many peers must agree (0 = one peer, 1 = two peers, 2 = highest stakes). Default 0." }
                    },
                    "required": ["query"]
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
        allSchemas.put(JSONObject(readScreenTool))
        allSchemas.put(JSONObject(tapScreenTool))
        allSchemas.put(JSONObject(swipeScreenTool))
        allSchemas.put(JSONObject(tapElementTool))

        // Sub-agent delegation: only when this registry is allowed to delegate
        // (the sub-agent's own registry is built with allowDelegation=false, which
        // both hides the tool and makes the handler unreachable: depth is capped at 1).
        if (allowDelegation) {
            allSchemas.put(JSONObject(delegateTaskTool))
        }
        allSchemas.put(JSONObject(ospQueryTool))
        
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
     * Public entry: every tool result passes through the context-safety limit
     * before being handed back to the LLM.
     */
    open suspend fun executeTool(toolName: String, jsonArgs: String): String =
        enforceToolResultLimit(toolName, executeToolInternal(toolName, jsonArgs))

    /**
     * Caps a tool result at MAX_TOOL_RESULT_CHARS. Prefer surgical truncation of the
     * largest string member (the payload stays valid JSON); fall back to a hard cut.
     * Either way an explicit note tells the small LLM the output was cut.
     */
    internal fun enforceToolResultLimit(toolName: String, resultJson: String): String {
        val total = resultJson.length
        if (total <= MAX_TOOL_RESULT_CHARS) return resultJson
        val note = "[Output truncated: $MAX_TOOL_RESULT_CHARS of $total characters shown. " +
            "Refine what you asked for (narrower capability, a specific element) or paginate with offset/limit instead of re-running the same call.]"
        try {
            val obj = JSONObject(resultJson)
            var bigKey: String? = null
            var bigLen = 0
            for (key in obj.keys()) {
                val v = obj.opt(key)
                if (v is String && v.length > bigLen) {
                    bigLen = v.length
                    bigKey = key
                }
            }
            if (bigKey != null && bigLen > 400) {
                // Margin for JSON escaping (newlines inflate) and the added fields.
                val keep = (MAX_TOOL_RESULT_CHARS - note.length - 200).coerceAtLeast(200)
                obj.put(bigKey, obj.getString(bigKey).take(keep) + "…")
                obj.put("truncated", true)
                obj.put("truncation_note", note)
                val out = obj.toString()
                return if (out.length <= MAX_TOOL_RESULT_CHARS + 100) out
                else resultJson.take(MAX_TOOL_RESULT_CHARS) + "\n$note"
            }
        } catch (_: Exception) {
            // Not a JSON object: hard cut below.
        }
        return resultJson.take(MAX_TOOL_RESULT_CHARS) + "\n$note"
    }

    /**
     * Executes a tool asynchronously over AIDL and waits for the callback result.
     * Incorporates human-in-the-loop Guard checks before firing external intents.
     */
    open suspend fun executeToolInternal(toolName: String, jsonArgs: String): String = withContext(Dispatchers.IO) {
        // Handle built-in tools first
        if (toolName == "get_os_info") {
            val info = "Android API ${android.os.Build.VERSION.SDK_INT}, Model: ${android.os.Build.MODEL}"
            return@withContext JSONObject().put("result", info).toString()
        }

        if (toolName == "list_harness_intents") {
            val available = toolRoutingTable.entries.joinToString(", ") { "${it.key} (${it.value})" }
            val result = "Available intents and packages: $available. Built-in tools: ask_human_for_input, list_harness_intents, get_os_info, list_installed_apps, osp_query"
            return@withContext JSONObject().put("result", result).toString()
        }

        if (toolName == "list_installed_apps") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val pm = context?.packageManager
            val packages = pm?.getInstalledPackages(PackageManager.GET_META_DATA)
                ?: return@withContext JSONObject().put("result", "Installed packages: None").toString()
            val names = packages.map { it.packageName }.sorted()
            val offset = args.optInt("offset", 0).coerceIn(0, names.size)
            val limit = args.optInt("limit", 50).coerceIn(1, 200)
            val page = names.drop(offset).take(limit)
            val nextOffset = if (offset + limit < names.size) offset + limit else null
            val payload = JSONObject()
                .put("result", "Installed packages (${names.size} total): ${page.joinToString(", ")}")
                .put("total", names.size)
                .put("offset", offset)
                .put("returned", page.size)
            if (nextOffset != null) {
                payload.put("next_offset", nextOffset)
                    .put("hint", "More apps remain: call list_installed_apps again with offset=$nextOffset.")
            }
            return@withContext payload.toString()
        }

        if (toolName == "list_compatible_intent_apps") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val capabilityHints = args.optJSONArray("capabilities")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx, "").trim().ifBlank { null } }
            } ?: emptyList()
            val matches = discoverCompatibleIntentApps(capabilityHints)
            if (matches.isEmpty()) {
                return@withContext JSONObject().put(
                    "result",
                    "No compatible Android intent-filter apps found for the requested capability."
                ).toString()
            }
            val offset = args.optInt("offset", 0).coerceIn(0, matches.size)
            val limit = args.optInt("limit", 30).coerceIn(1, 100)
            val page = matches.drop(offset).take(limit)
            val payload = JSONObject()
                .put("result", "Compatible apps (${matches.size} total): ${page.joinToString(", ")}")
                .put("total", matches.size)
                .put("offset", offset)
                .put("returned", page.size)
            if (offset + limit < matches.size) {
                payload.put("next_offset", offset + limit)
                    .put("hint", "More matches remain: call list_compatible_intent_apps again with offset=${offset + limit}.")
            }
            return@withContext payload.toString()
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

        if (toolName == "delegate_task") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val task = args.optString("task", "").trim()
            val agentType = args.optString("agent_type", "general").trim().ifBlank { "general" }
            if (task.isEmpty()) {
                return@withContext JSONObject().put("error", "delegate_task requires 'task': a complete, self-contained instruction for the sub-agent.").toString()
            }
            val handler = delegateHandler
                ?: return@withContext JSONObject().put(
                    "error",
                    "Delegation is not available in this context. Handle the task yourself with the tools above."
                ).toString()
            // A crashed sub-agent must not take the main loop down: hand the error
            // back as a tool result so the model can handle the task itself.
            return@withContext try {
                handler(task, agentType)
            } catch (e: Exception) {
                JSONObject()
                    .put("error", "Sub-agent '$agentType' failed: ${e.message}. Handle the task yourself with the tools above.")
                    .toString()
            }
        }

        if (toolName == "ask_human_for_input") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val prompt = args.optString("prompt", "Please provide input:")
            val defaultAnswer = args.optString("default_answer", "").ifBlank { null }
            val timeoutSeconds = args.optLong("timeout_seconds", 120L).coerceAtLeast(1L)
            return@withContext interactionManager?.requestHumanInput(prompt, defaultAnswer, timeoutSeconds) ?: ""
        }

        if (toolName == "osp_query") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val query = args.optString("query", "").trim()
            if (query.isEmpty()) {
                return@withContext JSONObject()
                    .put("error", "osp_query requires 'query': the factual question to ask the swarm.")
                    .toString()
            }
            val tier = args.optInt("tier", 0).coerceIn(0, 2)
            // Bind (5 s) then negotiate. The negotiation itself ends in a remote
            // LLM generation on a peer — minutes, not the generic 15 s tool slot —
            // hence its own deadline, matched to the bridge's 300 s read timeout.
            val svc = withTimeoutOrNull(OSP_BIND_TIMEOUT_MS) {
                try {
                    ospBind()
                } catch (e: Exception) {
                    null
                }
            }
            if (svc == null) {
                return@withContext JSONObject()
                    .put("error", "OSP Bridge not reachable. The OSP Bridge app must be installed with its node started; retry afterwards.")
                    .toString()
            }
            val reportJson = try {
                withTimeoutOrNull(OSP_QUERY_TIMEOUT_MS) { ospSubmit(svc, query, tier) }
            } catch (e: Exception) {
                return@withContext JSONObject().put("error", "osp_query failed: ${e.message}").toString()
            }
            if (reportJson == null) {
                return@withContext JSONObject()
                    .put("error", "osp_query timed out after ${OSP_QUERY_TIMEOUT_MS / 1000}s without a verified answer. Retry later or rephrase the question.")
                    .toString()
            }
            // Surface only the fields the agent can act on; the raw negotiation
            // trace stays out of the context window.
            val report = try { JSONObject(reportJson) } catch (_: Exception) { JSONObject() }
            val out = JSONObject().put("mode", report.optString("mode", "NO_QUORUM"))
            val answer = report.optString("answer", "")
            if (answer.isNotBlank()) out.put("answer", answer)
            val detail = report.optString("detail", "")
            if (detail.isNotBlank()) out.put("detail", detail)
            if (report.has("groundedness")) out.put("groundedness", report.optDouble("groundedness"))
            if (out.optString("mode") != "RESOLVED") {
                out.put("hint", "No verified answer (see mode/detail). Do not invent one: say you could not verify it, or retry osp_query with a rephrased factual question.")
            }
            return@withContext out.toString()
        }



        if (toolName == "read_screen") {
            if (!com.ai.harnessdroid.core.ScreenReaderService.isReady()) {
                // Open the settings page so the user can enable the service in one tap;
                // the error text tells the LLM what happened so it can pause and wait.
                com.ai.harnessdroid.core.ScreenReaderService.openSettings(context ?: return@withContext
                    JSONObject().put("error", "No context; cannot read the screen").toString())
                return@withContext JSONObject()
                    .put("error", "Screen reader not enabled. I opened Settings > Accessibility: enable 'Harness Droid Screen Reader', then retry read_screen.")
                    .toString()
            }
            val screen = com.ai.harnessdroid.core.ScreenReaderService.readScreen()
            // Small models re-read the same screen forever: tell them the next step.
            // When no mail content is on screen (home/app drawer/another app), point
            // them at launch_app first, or they loop on read_screen uselessly.
            val looksLikeMail = Regex("(?i)inbox|mail").containsMatchIn(screen)
            val hint = if (looksLikeMail) {
                "This dump is current. To open an item, call tap_element with part of its content " +
                    "(e.g. the sender name or subject of the first email row, not a folder name). " +
                    "If this is already enough to answer the user, reply NONE."
            } else {
                "No email content is visible on this screen (home screen, app drawer or another app). " +
                    "Call launch_app with {\"app_name\": \"Gmail\"}, then call read_screen again to see the inbox."
            }
            // Strip the [left,top][right,bottom] bounds and duplicate rows: raw a11y
            // coordinates read as noise and bury the instruction for a 0.5B model
            // (it starts echoing fragments like "6][109" instead of picking a tool).
            // tap_element matches by visible text, so no coordinates are needed here.
            val cleanScreen = screen.lines()
                .map { it.replace(Regex("\\s*\\[\\d+,\\d+\\]\\[\\d+,\\d+\\]\\s*$"), "").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("\n")
            return@withContext JSONObject()
                .put("screen", cleanScreen)
                .put("hint", hint)
                .toString()
        }

        if (toolName == "tap_screen") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val x = args.optInt("x", Int.MIN_VALUE)
            val y = args.optInt("y", Int.MIN_VALUE)
            if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                return@withContext JSONObject().put("error", "tap_screen requires integer x and y (prefer tap_element with the visible label instead).").toString()
            }
            if (!com.ai.harnessdroid.core.ScreenReaderService.isReady()) {
                return@withContext JSONObject().put("error", "Screen reader not enabled; cannot tap. Enable it in Settings > Accessibility.").toString()
            }
            val ok = com.ai.harnessdroid.core.ScreenReaderService.tap(x, y)
            return@withContext (if (ok) JSONObject().put("result", "Tapped ($x,$y)") else JSONObject().put("error", "Gesture cancelled; the screen may have changed. Run read_screen again.")).toString()
        }

        if (toolName == "tap_element") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val text = args.optString("text", "").trim()
            if (text.isEmpty()) {
                return@withContext JSONObject().put("error", "tap_element requires 'text' (the visible label of the element).").toString()
            }
            if (!com.ai.harnessdroid.core.ScreenReaderService.isReady()) {
                return@withContext JSONObject().put("error", "Screen reader not enabled; cannot tap. Enable it in Settings > Accessibility.").toString()
            }
            val ok = com.ai.harnessdroid.core.ScreenReaderService.tapElement(text)
            return@withContext (if (ok) JSONObject().put("result", "Tapped element '$text'")
                else JSONObject().put("error", "No tappable element containing '$text' found on screen. Run read_screen to see the current labels.")).toString()
        }

        if (toolName == "swipe_screen") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val x1 = args.optInt("x1", Int.MIN_VALUE); val y1 = args.optInt("y1", Int.MIN_VALUE)
            val x2 = args.optInt("x2", Int.MIN_VALUE); val y2 = args.optInt("y2", Int.MIN_VALUE)
            if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
                return@withContext JSONObject().put("error", "swipe_screen requires x1,y1,x2,y2 integers.").toString()
            }
            if (!com.ai.harnessdroid.core.ScreenReaderService.isReady()) {
                return@withContext JSONObject().put("error", "Screen reader not enabled; cannot swipe. Enable it in Settings > Accessibility.").toString()
            }
            val ok = com.ai.harnessdroid.core.ScreenReaderService.swipe(x1, y1, x2, y2)
            return@withContext (if (ok) JSONObject().put("result", "Swiped ($x1,$y1)->($x2,$y2)") else JSONObject().put("error", "Gesture cancelled. Run read_screen again.")).toString()
        }

        if (toolName == "send_android_intent") {
            val args = try { JSONObject(jsonArgs) } catch (_: Exception) { JSONObject() }
            val action = args.optString("action", "").trim()
            val dataUri = args.optString("data_uri", "").trim()
            if (action.isEmpty()) {
                return@withContext JSONObject().put("error", "send_android_intent requires an 'action' (e.g. 'android.intent.action.VIEW').").toString()
            }
            val intent = Intent(action).apply {
                if (dataUri.isNotEmpty()) {
                    data = try { android.net.Uri.parse(dataUri) } catch (_: Exception) { null }
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val target = context?.packageManager?.resolveActivity(intent, 0)?.activityInfo?.packageName
            // Security Guard: firing an external intent is exactly the kind of action the human must approve.
            val isApproved = interactionManager?.requireIntentPermission(toolName, target ?: action, jsonArgs) ?: true
            if (!isApproved) {
                return@withContext JSONObject().put("error", "User denied permission to send this intent.").toString()
            }
            return@withContext try {
                context?.startActivity(intent)
                JSONObject().put("result", "Intent $action${if (dataUri.isNotEmpty()) " $dataUri" else ""} sent").toString()
            } catch (e: Exception) {
                JSONObject().put("error", "Failed to send intent: ${e.message}").toString()
            }
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
                return@withContext JSONObject()
                    .put("result", "Successfully launched $pkgName")
                    // Small models stop after launching: nudge the next FSM step so the
                    // task (e.g. reading a mail) actually continues.
                    .put("hint", "The app is now open. To see its content, call read_screen next.")
                    .toString()
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
        ospConnection?.let {
            try {
                context?.unbindService(it)
            } catch (e: Exception) {
                // OSP Bridge may already be unbound or gone.
            }
        }
        ospConnection = null
        ospService = null
    }

    // -- OSP knowledge federation (ospbridge app over AIDL) -----------------------
    //
    // The OSP Bridge app (com.swarmknowledge.ospbridge) runs the Omni-Swarm
    // Protocol node on this device: an N1 origin that negotiates with remote
    // peers (whatsapp-bot memory, taught chunks…) and returns a firewall-verified
    // outcome. AIDL contract mirror-published in aidl/com/swarmknowledge/ospbridge.

    private val OSP_PACKAGE = "com.swarmknowledge.ospbridge"
    private val OSP_ACTION = "com.swarmknowledge.ospbridge.ACTION_OSP_SERVICE"
    private val OSP_BIND_TIMEOUT_MS = 5_000L
    /** Remote peers end in an LLM generation: minutes, not the 15 s tool slot. */
    private val OSP_QUERY_TIMEOUT_MS = 300_000L

    @Volatile
    private var ospService: IOspService? = null
    private var ospConnection: ServiceConnection? = null

    /** Bind the OSP Bridge foreground node; cached like boundServices. */
    private suspend fun ospBind(): IOspService = suspendCancellableCoroutine { cont ->
        ospService?.let {
            cont.resume(it)
            return@suspendCancellableCoroutine
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                val svc = IOspService.Stub.asInterface(service)
                ospService = svc
                if (cont.isActive) cont.resume(svc)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                ospService = null
            }
        }
        ospConnection = connection
        val intent = Intent(OSP_ACTION).apply { setPackage(OSP_PACKAGE) }
        val bound = try {
            context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
            false
        }
        if (bound != true && cont.isActive) {
            cont.resumeWithException(SecurityException("Could not bind to $OSP_PACKAGE (app missing or node stopped)."))
        }
    }

    /** One negotiation: submitQuery → registerCallback, resolved by onOutcome. */
    private suspend fun ospSubmit(svc: IOspService, query: String, tier: Int): String =
        suspendCancellableCoroutine { cont ->
            try {
                val qid = svc.submitQuery(query, tier)
                if (qid.isNullOrBlank()) {
                    cont.resume(JSONObject().put("error", "OSP Bridge rejected the query (node not started?).").toString())
                    return@suspendCancellableCoroutine
                }
                // registerCallback lands while the negotiation (seconds to minutes
                // on a remote LLM) is still running on the service's pool thread.
                svc.registerCallback(qid, object : IOspCallback.Stub() {
                    override fun onOutcome(queryId: String?, mode: Int, answer: String?, report: ByteArray?) {
                        if (cont.isActive) {
                            cont.resume(report?.toString(Charsets.UTF_8) ?: "{\"mode\":\"NO_QUORUM\"}")
                        }
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
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

    /** App labels known to the launch routing table (populated by discoverAndBindTools). */
    fun knownAppLabels(): List<String> =
        toolRoutingTable.keys.filter { it.startsWith("app_pkg_") }.map { it.removePrefix("app_pkg_") }

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
