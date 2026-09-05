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
            val step1Prompt = """
                $contextStr
                
                You are a helpful AI assistant. To solve the goal, you can use one of these tools:
                $toolSummaryList
                
                Which tool do you want to use next? 
                Reply ONLY with the exact name of the tool, or reply "NONE" if you are ready to give the final answer.
            """.trimIndent()
            
            forensicLogger.logEvent("FSM_STATE_1", "Asking LLM to pick a tool.")
            var toolChoice = llmClient.generateText(step1Prompt).trim()
            
            // Harness applies robust validation
            toolChoice = extractToolName(toolChoice, toolsArray)
            
            if (toolChoice == "NONE") {
                // FSM STATE 1b: Final Answer Generation
                val step1bPrompt = """
                    $contextStr
                    
                    You decided no more tools are needed. Please provide the final answer to the user.
                """.trimIndent()
                
                forensicLogger.logEvent("FSM_STATE_1B", "Asking LLM for final answer.")
                finalResult = llmClient.generateText(step1bPrompt).trim()
                sessionLog.add(SessionEvent("assistant", finalResult))
                sessionPersistence.flushLog(sessionLog)
                break
            }
            
            // FSM STATE 2: Argument Extraction
            val toolSchema = getToolSchema(toolChoice, toolsArray)
            val step2Prompt = """
                $contextStr
                
                You chose the tool: $toolChoice
                The required arguments schema is: 
                $toolSchema
                
                Reply ONLY with a valid JSON object containing the arguments for this tool.
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
        val upperOut = llmOutput.uppercase()
        if (upperOut.contains("NONE")) return "NONE"
        
        for (i in 0 until toolsArray.length()) {
            val name = toolsArray.getJSONObject(i).optString("name")
            if (llmOutput.contains(name, ignoreCase = true)) {
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
                "system" -> builder.append("System: ${event.content}\n")
                "user" -> builder.append("User: ${event.content}\n")
                "assistant" -> builder.append("Assistant: ${event.content}\n")
                "tool" -> builder.append("Tool [${event.toolName}]: ${event.content}\n")
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
