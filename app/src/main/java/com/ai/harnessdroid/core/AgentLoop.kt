package com.ai.harnessdroid.core

import android.content.Context
import android.util.Log
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.memory.ContextEmbedder
import com.ai.harnessdroid.memory.VectorStore
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The core thinking mechanism of harnessDroid.
 * Transformed into an explicit State-Machine Harness to support tiny LLMs (like Qwen2.5-0.5B or TinyLlama).
 * By explicitly guiding the LLM step-by-step through Intent -> Selection -> Argument Extraction,
 * we offload the orchestration cognitive load entirely onto the Harness.
 *
 * Context handling (HARNESS_CONTEXT_MODE):
 *  - "embd" (default): once the inline history exceeds MAX_CONTEXT_CHARS, new
 *    events are chunked, embedded and stored in the VectorStore; each FSM call
 *    receives the top-K chunk vectors as a latent prefix plus the question as
 *    text followup. Any failure falls back to the text path transparently.
 *  - "text": legacy ACH summarization, exact rollback of the old behaviour.
 */
class AgentLoop(
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry,
    private val sessionPersistence: SessionPersistence,
    private val forensicLogger: ForensicLogger,
    private val vectorStore: VectorStore? = null,
    private val memoryService: com.ai.harnessdroid.memory.MemoryService? = null
) {
    private val TAG = "AgentLoop"
    private var sessionLog = mutableListOf<SessionEvent>()
    // Compression and mitigation configuration. Read from system properties/env vars so JVM
    // unit tests can override them; on a real Android process these resolve to the defaults.
    private val MAX_CONTEXT_CHARS = (System.getProperty("HARNESS_MAX_CONTEXT_CHARS") ?: System.getenv("HARNESS_MAX_CONTEXT_CHARS") ?: "3000").toInt()
    private val COMPRESSION_STRATEGY = System.getProperty("HARNESS_COMPRESSION_STRATEGY") ?: System.getenv("HARNESS_COMPRESSION_STRATEGY") ?: "ach" // options: single, ach
    private val USE_MOCK_LLM = (System.getProperty("HARNESS_USE_MOCK_LLM") ?: System.getenv("HARNESS_USE_MOCK_LLM") ?: "0") == "1"
    private val CONTEXT_MODE = (System.getProperty("HARNESS_CONTEXT_MODE") ?: System.getenv("HARNESS_CONTEXT_MODE") ?: "embd").lowercase()

    /** Resolved on first use: >0 when the provider serves embeddings. */
    private var embeddingDim: Int = 0
    private var contextEmbedder: ContextEmbedder? = null

    private suspend fun ensureEmbedder(): ContextEmbedder? {
        contextEmbedder?.let { return it }
        if (CONTEXT_MODE != "embd") return null
        if (vectorStore == null) return null
        if (embeddingDim == 0) embeddingDim = llmClient.embeddingDim()
        if (embeddingDim <= 0) {
            forensicLogger.logEvent("EMBD_UNAVAILABLE", "provider dim=${embeddingDim}; staying on text mode")
            return null
        }
        contextEmbedder = ContextEmbedder(vectorStore) { text -> llmClient.embedText(text) }
        return contextEmbedder
    }

    /** One FSM generation: latent prefix when available, text otherwise. */
    private suspend fun generateWithContext(latentPrefix: FloatArray?, prompt: String, tag: String): String {
        if (latentPrefix != null && latentPrefix.isNotEmpty()) {
            try {
                val viaEmbd = llmClient.generateFromEmbeddings(
                    vectors = latentPrefix,
                    followupPrompt = prompt,
                    nPredict = 256
                )
                if (!viaEmbd.isNullOrBlank() && !viaEmbd.startsWith("Error")) {
                    forensicLogger.logEvent("EMBD_CTX_RESPONSE", "$tag answered via embeddings (${latentPrefix.size} floats)")
                    return viaEmbd
                }
                forensicLogger.logEvent("EMBD_CTX_REJECTED", "$tag embeddings call returned: ${viaEmbd?.take(80)}")
            } catch (e: Exception) {
                forensicLogger.logEvent("EMBD_CTX_ERROR", "$tag embeddings call failed: ${e.message}")
            }
            forensicLogger.logEvent("EMBD_FALLBACK_TEXT", "$tag fell back to the text path")
        }
        return llmClient.generateText(prompt)
    }

    private fun buildRetrievalQuery(taskInstruction: String, log: List<SessionEvent>): String {
        val lastPlan = log.lastOrNull { it.role == "assistant" }?.content ?: ""
        return if (lastPlan.isBlank()) taskInstruction else "$taskInstruction\n$lastPlan"
    }

    suspend fun runTask(taskInstruction: String, maxTurns: Int = 10): String = withContext(Dispatchers.IO) {
        // The session was purged before this task started (HarnessService),
        // so the embedder's ingest cursor must restart from zero too.
        contextEmbedder = null
        forensicLogger.logEvent("LOOP_INIT", "Discovering tools...")
        val toolSchemasRaw = toolRegistry.discoverAndBindTools()
        val toolsArray = try { JSONArray(toolSchemasRaw) } catch (e: Exception) { JSONArray() }
        
        // Harness simplifies the schema for the tiny LLM
        val toolSummaryList = buildToolSummary(toolsArray)

        // Ask the LLM for the Android capability set that best matches the task before we bind/filter tools.
        // This keeps the search space small, avoids relying on a single fallback tool like web_search,
        // and stays compatible with the intent-filter discovery used by the harness.
        forensicLogger.logEvent("PRE_QUERY_START", "Asking LLM for Android intent-compatible capability hints.")
        val preQueryPrompt = """
    <SYSTEM>
    You are an Android capability planner. Based ONLY on the user's task instruction, return a compact JSON array of up to 5 capability names that could be satisfied by standard Android intent-filter-compatible apps or services. Use short names like "web_search", "send_email", "open_browser", "dial_phone", "view_file", "assist_user", or "search_local_data". If none fit, return [] . Do not include prose or explanations.
    </SYSTEM>

    <TASK>
    $taskInstruction
    </TASK>
    """.trimIndent()

        var preferredToolRaw = try { llmClient.generateText(preQueryPrompt).trim() } catch (e: Exception) { "" }
        val capabilityHints = extractCapabilityHints(preferredToolRaw)
        forensicLogger.logEvent("PRE_QUERY_RESPONSE", "LLM capability hints: ${capabilityHints.joinToString()}")

        // Optionally ask the LLM a general clarifying question to optimize context
        forensicLogger.logEvent("GENERAL_QUESTION_START", "Asking LLM if a clarifying question is needed.")
        val generalQuestionPrompt = """
    <SYSTEM>
    You are an AI assistant. Given the task below, if you need a short clarifying question to pick the best tool, output that question only. If no clarification is needed, output NO_QUESTION.
    </SYSTEM>

    <TASK>
    $taskInstruction
    </TASK>
    """.trimIndent()

        val generalQuestionResponse: String = try { llmClient.generateText(generalQuestionPrompt).trim() } catch (e: Exception) { "NO_QUESTION" }
        forensicLogger.logEvent("GENERAL_QUESTION_RESPONSE", "LLM asked: $generalQuestionResponse")
        if (!generalQuestionResponse.equals("NO_QUESTION", ignoreCase = true) && generalQuestionResponse.isNotBlank()) {
            // Ask human for clarification via ToolRegistry helper (if available)
            val humanReply = try { toolRegistry.requestHumanInput(generalQuestionResponse) ?: "" } catch (e: Exception) { "" }
            forensicLogger.logEvent("HUMAN_CLARIFICATION", "Human replied: $humanReply")
            if (humanReply.isNotBlank()) {
                sessionLog.add(SessionEvent("user", humanReply))
                sessionPersistence.flushLog(sessionLog)
            }
        }

        sessionLog = sessionPersistence.loadLog()
        sessionLog.add(SessionEvent("system", "Goal: $taskInstruction"))
        sessionPersistence.flushLog(sessionLog)

        var turns = 0
        var finalResult = ""

        while (turns < maxTurns) {
            turns++
            forensicLogger.logEvent("TURN_START", "Starting turn $turns")
            
            // FSM STATE 1: Intent & Tool Selection
            var contextStr = buildContextString(sessionLog)
            // Latent prefix for this turn's FSM calls (null = text mode).
            var latentPrefix: FloatArray? = null
            // If the context is growing large, compress it using configured strategy
            if (contextStr.length > MAX_CONTEXT_CHARS) {
                val embedder = ensureEmbedder()
                if (embedder != null) {
                    // Embedding mode: the store IS the history memory. New events
                    // are chunkized+embedded, retrieval returns the top-K vectors
                    // injected as a latent prefix; the text history is elided.
                    forensicLogger.logEvent("EMBD_CTX_START", "Context length ${contextStr.length}, store=${vectorStore?.size}")
                    val startMs = System.currentTimeMillis()
                    try {
                        embedder.ingestNew(sessionLog)
                        val query = buildRetrievalQuery(taskInstruction, sessionLog)
                        val vectors = embedder.retrieveLatent(query, dim = embeddingDim)
                        val elapsed = System.currentTimeMillis() - startMs
                        if (vectors != null && vectors.isNotEmpty()) {
                            latentPrefix = vectors
                            val chunks = vectors.size / embeddingDim
                            contextStr = "[HISTORY: $chunks chunks injected as embeddings]"
                            forensicLogger.logEvent("EMBD_CTX_RESULT", "chunks=$chunks elapsed_ms=$elapsed store=${vectorStore?.size}")
                            // Elide: keep the original user request; the store
                            // holds everything else.
                            val originalRequest = sessionLog.firstOrNull { it.role == "user" }
                            sessionLog = mutableListOf()
                            originalRequest?.let { sessionLog.add(it) }
                            embedder.rewind(sessionLog.size)
                            sessionPersistence.flushLog(sessionLog)
                        } else {
                            forensicLogger.logEvent("EMBD_FALLBACK_TEXT", "retrieval returned no vectors (elapsed_ms=$elapsed); using ACH compression")
                            contextStr = compressTextPath(contextStr)
                        }
                    } catch (e: Exception) {
                        forensicLogger.logEvent("EMBD_CTX_ERROR", "Embedding ingest/retrieve failed: ${e.message}")
                        forensicLogger.logEvent("EMBD_FALLBACK_TEXT", "using ACH compression")
                        contextStr = compressTextPath(contextStr)
                    }
                } else {
                    contextStr = compressTextPath(contextStr)
                }
            }

            // Dual memory injection (F6): fact vectors join the latent prefix,
            // and by default the facts are ALSO rendered as a short text block
            // (a 0.5B model largely ignores soft prompts).
            var memoryText = ""
            if (memoryService != null) {
                try {
                    val factVectors = memoryService.recallVectors(taskInstruction, dim = embeddingDim)
                    if (factVectors != null) {
                        latentPrefix = if (latentPrefix == null) factVectors else latentPrefix!!.plus(factVectors)
                    }
                    memoryText = memoryService.buildMemoryPrefix(taskInstruction)
                    if (memoryText.isNotEmpty()) {
                        val factCount = memoryText.lines().count { it.startsWith("- ") }
                        forensicLogger.logEvent("MEMORY_INJECT", "injected $factCount user facts")
                    }
                } catch (e: Exception) {
                    forensicLogger.logEvent("MEMORY_ERROR", "memory injection failed: ${e.message}")
                }
            }
            val osInfo = "Android OS API ${android.os.Build.VERSION.SDK_INT}, Model: ${android.os.Build.MODEL}"
            // Filter tools based on the LLM's intent-compatible capability hints to reduce context.
            val preferenceText = if (capabilityHints.isEmpty()) preferredToolRaw else capabilityHints.joinToString(" ")
            val filteredToolsArray = try {
                filterToolsByPreference(preferenceText, toolsArray)
            } catch (e: Exception) {
                toolsArray
            }
            val filteredToolSummary = buildToolSummary(filteredToolsArray)

            val step1Prompt = """
<SYSTEM>
You are an AI Agent running on an Android device ($osInfo).
The harness is able to call different services on your behalf.
You can use these tools to execute tasks, and the harness will provide the results back to you.

AVAILABLE TOOLS:
$filteredToolSummary

RULES:
- You must write your step-by-step plan inside a <PLAN> block.
- As the final line INSIDE your <PLAN> block, you MUST output exactly: harness have to use <tool_name>
- If you have enough information to answer the user directly without a tool, output: NONE inside the <PLAN> block.

EXAMPLE OUTPUT FORMAT:
<PLAN>
1. Call get_os_info to check the device version.
2. Provide the OS information to the user.
harness have to use get_os_info
</PLAN>
</SYSTEM>

<CONVERSATION_HISTORY>
$contextStr
</CONVERSATION_HISTORY>

<INSTRUCTION>
Based on the conversation history, define your step-by-step plan in a <PLAN> block.
Then answer: which tool do you choose to use next?
Output exactly one of these: 'harness have to use <tool_name>' or 'NONE'.
</INSTRUCTION>
"""
.trimIndent()
            
            forensicLogger.logEvent("FSM_STATE_1", "Asking LLM to pick a tool.")
            val rawToolChoice = generateWithContext(latentPrefix, memoryText + step1Prompt, "FSM_STATE_1").trim()
            forensicLogger.logEvent("FSM_STATE_1_RESPONSE", "LLM replied: $rawToolChoice")
            var toolChoice = rawToolChoice
            
            // Harness applies robust validation against the filtered tool set
            toolChoice = extractToolName(toolChoice, filteredToolsArray)
            
            if (toolChoice == "NONE") {
                // FSM STATE 1b: Final Answer Generation
                val step1bPrompt = """
<SYSTEM>
You are an AI Agent running on an Android device ($osInfo).
You have access to the following tools via the harness:
$toolSummaryList

Review the CONVERSATION HISTORY below to see the results from any tools you used.
Synthesize these results and provide the final answer to the user in their preferred language.
If the user asks about your tools or capabilities, list them based on the tools above.
Do NOT talk about needing or not needing tools. Just answer the user directly.
</SYSTEM>

<CONVERSATION_HISTORY>
$contextStr
</CONVERSATION_HISTORY>

<INSTRUCTION>
Provide the final answer to the user based on the conversation and tool results above.
</INSTRUCTION>

<OUTPUT>
"""
.trimIndent()
                
                forensicLogger.logEvent("FSM_STATE_1B", "Asking LLM for final answer.")
                finalResult = generateWithContext(latentPrefix, memoryText + step1bPrompt, "FSM_STATE_1B").trim()
                sessionLog.add(SessionEvent("assistant", finalResult))
                sessionPersistence.flushLog(sessionLog)
                break
            }
            
            // FSM STATE 2: Argument Extraction
            val toolSchema = getToolSchema(toolChoice, toolsArray)
            val step2Prompt = """
<CONVERSATION_HISTORY>
$contextStr
</CONVERSATION_HISTORY>

<INSTRUCTION>
You chose the tool: $toolChoice
The required arguments schema is: 
$toolSchema

Reply ONLY with a valid JSON object containing the arguments for this tool.
Do NOT output any other text or explanation.
</INSTRUCTION>

<OUTPUT>
            """.trimIndent()
            
            forensicLogger.logEvent("FSM_STATE_2", "Asking LLM to generate arguments for $toolChoice.")
            val argsResponse = generateWithContext(latentPrefix, memoryText + step2Prompt, "FSM_STATE_2").trim()
            val arguments = cleanJson(argsResponse)
            
            forensicLogger.logEvent("PLAN_TOOL_CALL", "Executing '$toolChoice' with args: $arguments")
            sessionLog.add(SessionEvent("assistant", "Calling tool: $toolChoice with $arguments"))
            sessionPersistence.flushLog(sessionLog)
            
            // FSM STATE 3: Tool Execution (Harness)
            val toolResultJson = toolRegistry.executeTool(toolChoice, arguments)
            
            forensicLogger.logEvent("TOOL_RESULT", "Result from '$toolChoice': $toolResultJson")
            sessionLog.add(SessionEvent("tool", toolResultJson, toolName = toolChoice))
            sessionPersistence.flushLog(sessionLog)
        }
        
        if (turns >= maxTurns) {
            finalResult = "Error: Maximum turns reached without completing the task."
            forensicLogger.logEvent("ERROR", finalResult)
            sessionLog.add(SessionEvent("system", finalResult))
            sessionPersistence.flushLog(sessionLog)
        }

        // Learn from the task: pull durable user facts out of the transcript
        // (one greedy text call; skipped for the mock LLM used in CI).
        if (memoryService != null && !USE_MOCK_LLM) {
            try {
                val stored = memoryService.extractMemories(sessionLog)
                forensicLogger.logEvent("MEMORY_EXTRACT", "$stored new facts stored")
            } catch (e: Exception) {
                forensicLogger.logEvent("MEMORY_EXTRACT_ERROR", "extraction failed: ${e.message}")
            }
        }

        // Persist the embedding store at the end of every task run.
        if (vectorStore != null && contextEmbedder != null) {
            try {
                val stored = vectorStore.size
                vectorStore.flush(force = true)
                forensicLogger.logEvent("VECTOR_STORE_FLUSH", "persisted $stored vectors")
            } catch (e: Exception) {
                forensicLogger.logEvent("VECTOR_STORE_FLUSH_ERROR", "flush failed: ${e.message}")
            }
        }

        return@withContext finalResult
    }

    private fun buildToolSummary(toolsArray: JSONArray): String {
        val sb = StringBuilder()
        sb.append("- NONE: Select this if you have the final answer and do not need a tool.\n")
        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            val name = tool.optString("name")
            val desc = tool.optString("description")
            sb.append("- $name: $desc\n")
        }
        return sb.toString()
    }
    
    private fun extractToolName(llmOutput: String, toolsArray: JSONArray): String {
        val trimmed = llmOutput.trim()
        val lowerOut = trimmed.lowercase()

        val triggerPhrase = "harness have to use"
        if (lowerOut.contains(triggerPhrase)) {
            val afterPhrase = lowerOut.substringAfterLast(triggerPhrase).trim()
            // Find which tool name follows
            for (i in 0 until toolsArray.length()) {
                val name = toolsArray.getJSONObject(i).optString("name")
                if (afterPhrase.startsWith(name.lowercase()) || afterPhrase.contains(name.lowercase())) {
                    return name
                }
            }
        }

        // Check if NONE is the intended action (e.g. at the end of the output or before </plan>)
        val lastLine = trimmed.lines().lastOrNull { it.isNotBlank() }?.lowercase()?.trim() ?: ""
        val lastLineAlpha = lastLine.replace(Regex("[^a-z]"), "")
        if (lowerOut.endsWith("none") || lowerOut == "none" || lastLineAlpha == "none" ||
            lowerOut.contains("none\n</plan>") || lowerOut.contains("\nnone\n") || lowerOut.contains("none</plan>")) {
            return "NONE"
        }

        // Fallback: Check if they mentioned the tool name ANYWHERE in the output despite instructions
        for (i in 0 until toolsArray.length()) {
            val name = toolsArray.getJSONObject(i).optString("name")
            if (lowerOut.contains(name.lowercase())) {
                return name
            }
        }

        // If still no match, be conservative and return NONE (final-answer path) instead of
        // executing an arbitrary tool. The old "first available tool" fallback forced unwanted
        // tool executions in production whenever the LLM output went off-format.
        return "NONE"
    }
    
    private fun getToolSchema(toolName: String, toolsArray: JSONArray): String {
        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            if (tool.optString("name") == toolName) {
                return tool.optJSONObject("parameters")?.toString(2) ?: "{}"
            }
        }
        return "{}"
    }

    private fun extractCapabilityHints(rawResponse: String?): List<String> {
        if (rawResponse.isNullOrBlank()) return emptyList()
        val cleaned = rawResponse.trim()
        val start = cleaned.indexOf("[")
        val end = cleaned.lastIndexOf("]")
        if (start != -1 && end != -1 && end > start) {
            val payload = cleaned.substring(start, end + 1)
            return try {
                JSONArray(payload).let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        val value = arr.optString(idx, "").trim()
                        if (value.isNotEmpty()) value else null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Fallback: keep the first few short phrases if the model emitted plain text.
        return cleaned
            .split(Regex("[^a-zA-Z0-9_]+"))
            .filter { it.isNotBlank() && it.length <= 30 }
            .take(5)
    }

    private fun filterToolsByPreference(preferredRaw: String?, toolsArray: JSONArray): JSONArray {
        if (preferredRaw == null) return toolsArray
        val preferred = preferredRaw.trim().lowercase()
        if (preferred.isEmpty() || preferred == "none" || preferred == "[]") return toolsArray

        forensicLogger.logEvent("FILTER_START", "Filtering tools for preference: $preferredRaw")

        // Core generic tools stay visible regardless of capability hints: a small LLM that
        // changes strategy mid-task must still be able to launch an app or send an intent.
        val coreTools = setOf(
            "launch_app", "send_android_intent", "list_installed_apps",
            "list_compatible_intent_apps", "get_os_info", "ask_human_for_input"
        )

        val tokens = preferred.split(Regex("\\s+|[,\\-]"))
        val out = JSONArray()
        for (i in 0 until toolsArray.length()) {
            val t = toolsArray.getJSONObject(i)
            val name = t.optString("name", "").lowercase()
            val desc = t.optString("description", "").lowercase()
            if (name in coreTools) {
                out.put(t)
                continue
            }
            var matched = false
            for (tok in tokens) {
                if (tok.isBlank()) continue
                if (name.contains(tok) || desc.contains(tok) || name.startsWith(tok)) {
                    matched = true
                    break
                }
            }
            if (matched) out.put(t)
        }

        // If no matches, be conservative and return the full list
        if (out.length() == 0) {
            forensicLogger.logEvent("FILTER_NONE", "No close matches found for: $preferredRaw. Falling back to full tool list.")
            return toolsArray
        }

        forensicLogger.logEvent("FILTER_RESULT", "Filtered tools count: ${out.length()}")
        return out
    }

    /** Legacy text compression path (ACH/single): summarize, wrap and elide. */
    private suspend fun compressTextPath(contextStr: String): String {
        forensicLogger.logEvent("CONTEXT_COMPRESSION_START", "Context length ${contextStr.length}, strategy=$COMPRESSION_STRATEGY")
        val startMs = System.currentTimeMillis()
        val compressed = try {
            performCompression(contextStr)
        } catch (e: Exception) {
            forensicLogger.logEvent("CONTEXT_COMPRESSION_ERROR", "Compression failed: ${e.message}")
            contextStr
        }
        val elapsed = System.currentTimeMillis() - startMs
        forensicLogger.logEvent("CONTEXT_COMPRESSION_RESULT", "Compressed length ${compressed.length}, elapsed_ms=$elapsed")
        // Elide the history: keep the original user request, replace everything else
        // with the summary. Without elision the summaries accumulate and every turn
        // re-compresses an ever-growing log.
        val originalRequest = sessionLog.firstOrNull { it.role == "user" }
        sessionLog = mutableListOf()
        originalRequest?.let { sessionLog.add(it) }
        sessionLog.add(SessionEvent("system", "CompressedConversationSummary: $compressed"))
        sessionPersistence.flushLog(sessionLog)
        return "<COMPRESSED_HISTORY>\n$compressed\n</COMPRESSED_HISTORY>"
    }

    private fun buildContextString(log: List<SessionEvent>): String {
        val builder = java.lang.StringBuilder()
        for (event in log) {
            when (event.role) {
                "system" -> builder.append("<SYSTEM_MSG>\n${event.content}\n</SYSTEM_MSG>\n")
                "user" -> builder.append("<USER_MSG>\n${event.content}\n</USER_MSG>\n")
                "assistant" -> builder.append("<ASSISTANT_MSG>\n${event.content}\n</ASSISTANT_MSG>\n")
                "tool" -> builder.append("<TOOL_RESULT name=\"${event.toolName}\">\n${event.content}\n</TOOL_RESULT>\n")
            }
        }
        return builder.toString()
    }

    // Top-level compression dispatcher
    private suspend fun performCompression(contextStr: String): String = withContext(Dispatchers.IO) {
        if (USE_MOCK_LLM) {
            forensicLogger.logEvent("CONTEXT_COMPRESSION_MODE", "Using MOCK LLM compression (fast)")
            return@withContext performMockCompression(contextStr)
        }

        return@withContext when (COMPRESSION_STRATEGY.lowercase()) {
            "ach" -> performACHCompression(contextStr)
            else -> performSingleCompression(contextStr)
        }
    }

    // Original single-call compression
    private suspend fun performSingleCompression(contextStr: String): String = withContext(Dispatchers.IO) {
        val compressionPrompt = """
<SYSTEM>
You are an assistant tasked with compressing a conversation history for an autonomous agent. Produce a short concise summary (max 600 characters) that preserves important facts, tool outputs, and unresolved user goals. Output only the compressed summary.
</SYSTEM>

<CONVERSATION_HISTORY>
$contextStr
</CONVERSATION_HISTORY>
""".trimIndent()
        return@withContext try {
            llmClient.generateText(compressionPrompt).trim()
        } catch (e: Exception) {
            contextStr
        }
    }

    // Adaptive Chunked History (ACH) compression: split context into chunks, summarize each, then summarize the summaries
    private suspend fun performACHCompression(contextStr: String): String = withContext(Dispatchers.IO) {
        val chunkSize = 2000
        val summaries = mutableListOf<String>()
        var start = 0
        var idx = 0
        while (start < contextStr.length) {
            val end = kotlin.math.min(start + chunkSize, contextStr.length)
            val chunk = contextStr.substring(start, end)
            val chunkPrompt = """
<SYSTEM>
Compress the following conversation chunk into a short summary (max 300 characters) preserving facts and tool outputs. Output only the summary.
</SYSTEM>

<CHUNK>
$chunk
</CHUNK>
""".trimIndent()
            forensicLogger.logEvent("ACH_CHUNK_SUMMARY_START", "chunk=$idx start=$start end=$end")
            val chunkSummary = try {
                llmClient.generateText(chunkPrompt).trim()
            } catch (e: Exception) {
                chunk.take(300)
            }
            forensicLogger.logEvent("ACH_CHUNK_SUMMARY_DONE", "chunk=$idx len=${chunkSummary.length}")
            summaries.add(chunkSummary)
            idx++
            start = end
        }

        // Combine summaries and compress once more
        val combined = summaries.joinToString(separator = "\n")
        val finalPrompt = """
<SYSTEM>
You are an assistant tasked with producing a concise combined summary (max 600 characters) from the list of chunk summaries below. Preserve key facts and unresolved goals. Output only the final summary.
</SYSTEM>

<CHUNK_SUMMARIES>
$combined
</CHUNK_SUMMARIES>
""".trimIndent()
        return@withContext try {
            llmClient.generateText(finalPrompt).trim()
        } catch (e: Exception) {
            // fallback: join chunk summaries truncated
            combined.take(600)
        }
    }

    // Very fast mock compression used for CI/remote testing to avoid heavy LLM runs
    private fun performMockCompression(contextStr: String): String {
        // Prefer extracting recent assistant/tool messages and then truncate
        val marker = "</TOOL_RESULT>"
        val idx = contextStr.lastIndexOf(marker)
        val snippet = if (idx != -1 && idx + marker.length < contextStr.length) {
            contextStr.substring(idx + marker.length)
        } else {
            contextStr.takeLast(800)
        }
        // collapse whitespace and truncate
        return snippet.replace(Regex("\\s+"), " ").trim().take(600)
    }

    private fun cleanJson(response: String): String {
        var cleanJson = response.trim()
        if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substringAfter("```json")
        else if (cleanJson.startsWith("```")) cleanJson = cleanJson.substringAfter("```")
        
        if (cleanJson.contains("```")) cleanJson = cleanJson.substringBeforeLast("```")
        
        // If the tiny LLM completely failed, provide empty object as fallback guardrail
        if (!cleanJson.trim().startsWith("{")) {
            val firstBrace = cleanJson.indexOf("{")
            val lastBrace = cleanJson.lastIndexOf("}")
            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                cleanJson = cleanJson.substring(firstBrace, lastBrace + 1)
            } else {
                return "{}"
            }
        }
        return cleanJson.trim()
    }
}
