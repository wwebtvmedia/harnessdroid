package com.ai.harnessdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class AgentLoopTest {

    @Test
    fun testLoopStopsWhenMaxTurnsReached() = runBlocking {
        // LLM keeps saying "mockTool" forever
        val mockLlmClient = MockLLMClient(infiniteTool = true)
        val mockToolRegistry = MockToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        val result = agentLoop.runTask("Do something", maxTurns = 3)
        println("TEST RESULT: " + result)
        assertTrue(result.contains("Maximum turns reached"))
        assertEquals(3, mockToolRegistry.executionCount)
    }

    @Test
    fun testLoopSuccessfullyExecutesToolAndReturnsFinalAnswer() = runBlocking {
        // LLM says "mockTool" once, then "NONE"
        val mockLlmClient = MockLLMClient(infiniteTool = false)
        val mockToolRegistry = MockToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        val result = agentLoop.runTask("Get the weather", maxTurns = 5)
        
        assertTrue(result.contains("Final Answer"))
        assertEquals(1, mockToolRegistry.executionCount)
    }
    @Test
    fun testListToolsSuccessfullyReturnsList() = runBlocking {
        // LLM says "list_harness_intents", then "NONE"
        val mockLlmClient = MockLLMClient(infiniteTool = false)
        val mockToolRegistry = MockToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        val result = agentLoop.runTask("please list all tools accessible", maxTurns = 5)
        
        assertTrue(result.contains("Available intents"))
        assertEquals(1, mockToolRegistry.executionCount)
    }

    @Test
    fun testRecursiveMailSummarizationUsesCompatibleIntentApps() = runBlocking {
        val mockLlmClient = RecursiveMailLLMClient()
        val mockToolRegistry = RecursiveMailToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        val result = agentLoop.runTask("Summarize my newest inbox mail", maxTurns = 6)

        assertTrue(result.contains("mail") || result.contains("Gmail") || result.contains("summary"))
        assertTrue(mockToolRegistry.executionCount >= 2)
    }
}

class MockLLMClient(private val infiniteTool: Boolean) : com.ai.harnessdroid.llm.LLMClient(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    private var state = 0
    override suspend fun generateText(prompt: String): String {
        println("MOCK LLM PROMPT: " + prompt)
        val lower = prompt.lowercase()
        return if (lower.contains("available tools") || lower.contains("<plan") || lower.contains("which tool") || lower.contains("choose") || lower.contains("pick a tool")) {
            if (infiniteTool) {
                "<PLAN>Doing something</PLAN>\nharness have to use mockTool"
            } else {
                if (state == 0) {
                    state = 1
                    if (lower.contains("goal: please list all tools accessible") || lower.contains("please list all tools accessible")) {
                        "<PLAN>Listing tools</PLAN>\nharness have to use list_harness_intents"
                    } else {
                        "<PLAN>Mocking</PLAN>\nharness have to use mockTool"
                    }
                } else {
                    "<PLAN>Done</PLAN>\nNONE"
                }
            }
        } else if (lower.contains("json") && lower.contains("arguments")) {
            "{ \"testArg\": \"val\" }"
        } else {
            // FSM STATE 1b: Final Answer Generation
            if (lower.contains("goal: please list all tools accessible") || lower.contains("please list all tools accessible")) {
                "Available intents: mockTool, list_harness_intents"
            } else {
                "Final Answer"
            }
        }
    }
}

class MockToolRegistry : com.ai.harnessdroid.tools.ToolRegistry(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?, null) {
    var executionCount = 0
    override suspend fun discoverAndBindTools(): String {
        val tool = JSONObject().apply {
            put("name", "mockTool")
            put("description", "A mock tool")
        }
        val listTool = JSONObject().apply {
            put("name", "list_harness_intents")
            put("description", "List intents")
        }
        return JSONArray().put(tool).put(listTool).toString()
    }
    override suspend fun executeTool(toolName: String, jsonArgs: String): String {
        executionCount++
        return "{\"result\": \"success\"}"
    }
}

class RecursiveMailLLMClient : com.ai.harnessdroid.llm.LLMClient(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    private var state = 0
    override suspend fun generateText(prompt: String): String {
        val lower = prompt.lowercase()
        if (lower.contains("android capability planner") || lower.contains("capability names")) {
            return "[\"read_mail\", \"open_email_app\"]"
        }
        if (lower.contains("available tools") || lower.contains("which tool") || lower.contains("choose") || lower.contains("pick a tool")) {
            if (state == 0) {
                state += 1
                return "<PLAN>Find compatible app</PLAN>\nharness have to use list_compatible_intent_apps"
            }
            return "<PLAN>Summarize the mail</PLAN>\nNONE"
        }
        if (lower.contains("json") && lower.contains("arguments")) {
            return "{ \"capabilities\": [\"read_mail\", \"open_email_app\"] }"
        }
        return "Mail summary: newest inbox message is a product update and needs no action."
    }
}

class RecursiveMailToolRegistry : com.ai.harnessdroid.tools.ToolRegistry(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?, null) {
    var executionCount = 0
    override suspend fun discoverAndBindTools(): String {
        val tool1 = JSONObject().apply {
            put("name", "list_compatible_intent_apps")
            put("description", "Find apps matching Android intent capabilities")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("capabilities", JSONObject().apply {
                        put("type", "array")
                    })
                })
            })
        }
        val tool2 = JSONObject().apply {
            put("name", "launch_app")
            put("description", "Launch installed app")
        }
        return JSONArray().put(tool1).put(tool2).toString()
    }
    override suspend fun executeTool(toolName: String, jsonArgs: String): String {
        executionCount++
        return if (toolName == "list_compatible_intent_apps") {
            "{\"result\": \"Compatible apps: Gmail, Outlook\"}"
        } else {
            "{\"result\": \"Opened Gmail\"}"
        }
    }
}

class MockSessionPersistence : SessionPersistence(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?, "test") {
    private val memory = mutableListOf<SessionEvent>()
    override suspend fun flushLog(log: List<SessionEvent>) { memory.addAll(log) }
    override suspend fun loadLog(): MutableList<SessionEvent> = mutableListOf()
}

class ForensicLoggerMock : ForensicLogger(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    override fun logEvent(tag: String, message: String) {}
}
