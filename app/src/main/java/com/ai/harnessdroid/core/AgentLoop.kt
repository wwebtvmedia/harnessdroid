package com.ai.harnessdroid.core

import android.content.Context
import android.util.Log
import com.ai.harnessdroid.llm.LLMClient
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
 */
class AgentLoop(
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry,
    private val sessionPersistence: SessionPersistence,
    private val forensicLogger: ForensicLogger
) {
    private val TAG = "AgentLoop"
    private var sessionLog = mutableListOf<SessionEvent>()

    suspend fun runTask(taskInstruction: String, maxTurns: Int = 10): String = withContext(Dispatchers.IO) {
        forensicLogger.logEvent("LOOP_INIT", "Discovering tools...")
        val toolSchemasRaw = toolRegistry.discoverAndBindTools()
        val toolsArray = try { JSONArray(toolSchemasRaw) } catch (e: Exception) { JSONArray() }
        
        // Harness simplifies the schema for the tiny LLM
        val toolSummaryList = buildToolSummary(toolsArray)

        sessionLog = sessionPersistence.loadLog()
        sessionLog.add(SessionEvent("system", "Goal: $taskInstruction"))
        sessionPersistence.flushLog(sessionLog)

        var turns = 0
        var finalResult = ""

        while (turns < maxTurns) {
            turns++
            forensicLogger.logEvent("TURN_START", "Starting turn $turns")
            
            // FSM STATE 1: Intent & Tool Selection
            val contextStr = buildContextString(sessionLog)
            val osInfo = "Android OS API ${android.os.Build.VERSION.SDK_INT}, Model: ${android.os.Build.MODEL}"
            val step1Prompt = """
<SYSTEM>
You are an AI Agent running on an Android device ($osInfo).
The harness is able to call different services on your behalf, including providing the list of accessible tools.
You can use these tools to implement services, execute tasks, and the harness will provide the results as feedback to you.
The user may speak to you in any language.

AVAILABLE TOOLS:
$toolSummaryList

RULES:
- To use a tool, you MUST output exactly the phrase: harness have to use <tool_name>
- Do NOT output any other text, reasoning, or translation.
- If the user wants to list tools, output: harness have to use list_harness_intents
- If the user asks about OS/device, output: harness have to use get_os_info
- If you have enough information to answer the user directly without a tool, output: NONE

EXAMPLES:
<CONVERSATION_HISTORY>
<USER_MSG>please list all tools accessible</USER_MSG>
</CONVERSATION_HISTORY>
<INSTRUCTION>Based on the conversation history, which tool do you choose to use next? Output 'harness have to use <tool_name>', or NONE.</INSTRUCTION>
<OUTPUT>
harness have to use list_harness_intents
</OUTPUT>

<CONVERSATION_HISTORY>
<USER_MSG>what os is this?</USER_MSG>
</CONVERSATION_HISTORY>
<INSTRUCTION>Based on the conversation history, which tool do you choose to use next? Output 'harness have to use <tool_name>', or NONE.</INSTRUCTION>
<OUTPUT>
harness have to use get_os_info
</OUTPUT>

<CONVERSATION_HISTORY>
<USER_MSG>what os is this?</USER_MSG>
<TOOL_RESULT name="get_os_info">{"result": "Android API 34, Model: Pixel 7"}</TOOL_RESULT>
</CONVERSATION_HISTORY>
<INSTRUCTION>Based on the conversation history, which tool do you choose to use next? Output 'harness have to use <tool_name>', or NONE.</INSTRUCTION>
<OUTPUT>
NONE
</OUTPUT>
</SYSTEM>

<CONVERSATION_HISTORY>
$contextStr
</CONVERSATION_HISTORY>

<INSTRUCTION>
Based on the conversation history, which tool do you choose to use next?
Output 'harness have to use <tool_name>', or NONE.
</INSTRUCTION>

<OUTPUT>
"""
.trimIndent()
            
            forensicLogger.logEvent("FSM_STATE_1", "Asking LLM to pick a tool.")
            val rawToolChoice = llmClient.generateText(step1Prompt).trim()
            forensicLogger.logEvent("FSM_STATE_1_RESPONSE", "LLM replied: $rawToolChoice")
            var toolChoice = rawToolChoice
            
            // Harness applies robust validation
            toolChoice = extractToolName(toolChoice, toolsArray)
            
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
                finalResult = llmClient.generateText(step1bPrompt).trim()
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
            val argsResponse = llmClient.generateText(step2Prompt).trim()
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

        // Exact match for NONE
        if (lowerOut == "none") return "NONE"
        if (lowerOut.startsWith("none") || lowerOut.contains("none")) {
            return "NONE"
        }

        val triggerPhrase = "harness have to use"
        if (lowerOut.contains(triggerPhrase)) {
            val afterPhrase = lowerOut.substringAfter(triggerPhrase).trim()
            // Find which tool name follows
            for (i in 0 until toolsArray.length()) {
                val name = toolsArray.getJSONObject(i).optString("name")
                if (afterPhrase.startsWith(name.lowercase()) || afterPhrase.contains(name.lowercase())) {
                    return name
                }
            }
        }
        
        // Fallback: Check if they just outputted the tool name despite instructions
        for (i in 0 until toolsArray.length()) {
            val name = toolsArray.getJSONObject(i).optString("name")
            if (trimmed.equals(name, ignoreCase = true) || lowerOut.contains(name.lowercase())) {
                return name
            }
        }
        
        return "NONE" // Fallback guardrail
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
