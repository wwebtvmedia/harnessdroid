package com.tree4five.harness.core

import android.content.Context
import android.util.Log
import com.tree4five.harness.llm.LLMClient
import com.tree4five.harness.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The core thinking mechanism of harnessDroid.
 * Uses coarse-grained parallelism and suspend functions to coordinate
 * the Brain (LLMProvider) and the Hands (ToolRegistry).
 * Now integrates a compressed, timestamped NoSQL memory persistence layer
 * and Forensic Logging for safety.
 */
class AgentLoop(
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry,
    private val sessionPersistence: SessionPersistence,
    private val forensicLogger: ForensicLogger
) {
    private val TAG = "AgentLoop"
    
    // In-memory working log (State Restoration loads this on boot)
    private var sessionLog = mutableListOf<SessionEvent>()

    /**
     * Executes an autonomous loop to fulfill a user task.
     * Keeps running until the LLM produces a final answer without calling tools.
     */
    suspend fun runTask(taskInstruction: String, maxTurns: Int = 10): String = withContext(Dispatchers.IO) {
        forensicLogger.logEvent("LOOP_INIT", "Discovering tools...")
        // 1. Discover all tools securely via IPC
        val toolSchemas = toolRegistry.discoverAndBindTools()
        
        // 2. State Restoration: Load past memory
        sessionLog = sessionPersistence.loadLog()
        
        // 3. Assemble the System Prompt (Context Construction)
        val systemPrompt = """
            You are harnessDroid, an autonomous agent running on an Android embedded system.
            You have access to the following tools via IPC bindings:
            $toolSchemas
            
            To use a tool, you MUST reply with a pure JSON object in this exact format:
            { "tool_call": { "name": "tool_name", "arguments": { ... } } }
            
            Do not include markdown or explanations if you are calling a tool.
            If you have completed the task or cannot proceed, reply with standard text (no JSON).
        """.trimIndent()
        
        // Append context and flush checkpoint
        sessionLog.add(SessionEvent("system", systemPrompt))
        sessionLog.add(SessionEvent("user", taskInstruction))
        sessionPersistence.flushLog(sessionLog)

        var turns = 0
        var finalResult = ""

        while (turns < maxTurns) {
            turns++
            forensicLogger.logEvent("TURN_START", "Starting turn $turns")
            
            // Build the prompt from memory
            val currentContext = buildContextString(sessionLog)
            
            // Think: Query the Brain (LLMProvider)
            forensicLogger.logEvent("THINKING", "Querying LLM with current context size: ${currentContext.length}")
            val llmResponse = llmClient.generateText(currentContext)
            
            // Parse response for tool calls
            val toolCall = parseToolCall(llmResponse)
            
            if (toolCall != null) {
                val toolName = toolCall.getString("name")
                val arguments = toolCall.getJSONObject("arguments").toString()
                
                // Record the LLM's thought and plan (Tool Call)
                forensicLogger.logEvent("PLAN_TOOL_CALL", "Agent decided to use tool '$toolName' with args: $arguments")
                sessionLog.add(SessionEvent("assistant", "Calling tool: $toolName with $arguments"))
                sessionPersistence.flushLog(sessionLog)
                
                // Act: Execute the tool via Registry (IPC)
                val toolResultJson = toolRegistry.executeTool(toolName, arguments)
                
                // Record the environment's response (Tool Result)
                forensicLogger.logEvent("TOOL_RESULT", "Result from '$toolName': $toolResultJson")
                sessionLog.add(SessionEvent("tool", toolResultJson, toolName = toolName))
                sessionPersistence.flushLog(sessionLog)
                
            } else {
                // No tool call detected, this is the final answer
                finalResult = llmResponse
                forensicLogger.logEvent("FINAL_ANSWER", "Agent provided final text: $finalResult")
                sessionLog.add(SessionEvent("assistant", finalResult))
                sessionPersistence.flushLog(sessionLog)
                break
            }
        }
        
        if (turns >= maxTurns) {
            finalResult = "Error: Maximum turns reached without completing the task."
            forensicLogger.logEvent("ERROR", finalResult)
            sessionLog.add(SessionEvent("system", finalResult))
            sessionPersistence.flushLog(sessionLog)
        }

        return@withContext finalResult
    }

    private fun buildContextString(log: List<SessionEvent>): String {
        val builder = java.lang.StringBuilder()
        for (event in log) {
            when (event.role) {
                "system" -> builder.append("System: ${event.content}\n")
                "user" -> builder.append("User [${event.timestamp}]: ${event.content}\n")
                "assistant" -> builder.append("Assistant [${event.timestamp}]: ${event.content}\n")
                "tool" -> builder.append("Tool [${event.toolName}] [${event.timestamp}]: ${event.content}\n")
            }
        }
        builder.append("Assistant: ")
        return builder.toString()
    }

    /**
     * Attempts to parse a pure JSON tool call from the LLM response.
     * Safely strips markdown blocks if the LLM hallucinated them.
     */
    private fun parseToolCall(response: String): JSONObject? {
        var cleanJson = response.trim()
        if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substringAfter("```json")
        else if (cleanJson.startsWith("```")) cleanJson = cleanJson.substringAfter("```")
        
        if (cleanJson.contains("```")) cleanJson = cleanJson.substringBeforeLast("```")
        cleanJson = cleanJson.trim()
        
        return try {
            val obj = JSONObject(cleanJson)
            if (obj.has("tool_call")) obj.getJSONObject("tool_call") else null
        } catch (e: Exception) {
            null
        }
    }
}
