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
}

class MockLLMClient(private val infiniteTool: Boolean) : com.ai.harnessdroid.llm.LLMClient(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    private var state = 0
    override suspend fun generateText(prompt: String): String {
        println("MOCK LLM PROMPT: " + prompt)
        return if (prompt.contains("choose which tool to use next")) {
            if (infiniteTool) {
                "<PLAN>Doing something</PLAN>\nharness have to use mockTool"
            } else {
                if (state == 0) {
                    state = 1
                    if (prompt.contains("Goal: please list all tools accessible")) {
                        "<PLAN>Listing tools</PLAN>\nharness have to use list_harness_intents"
                    } else {
                        "<PLAN>Mocking</PLAN>\nharness have to use mockTool"
                    }
                } else {
                    "<PLAN>Done</PLAN>\nNONE"
                }
            }
        } else if (prompt.contains("JSON object containing the arguments")) {
            "{ \"testArg\": \"val\" }"
        } else {
            // FSM STATE 1b: Final Answer Generation
            if (prompt.contains("Goal: please list all tools accessible")) {
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

class MockSessionPersistence : SessionPersistence(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?, "test") {
    private val memory = mutableListOf<SessionEvent>()
    override suspend fun flushLog(log: List<SessionEvent>) { memory.addAll(log) }
    override suspend fun loadLog(): MutableList<SessionEvent> = mutableListOf()
}

class ForensicLoggerMock : ForensicLogger(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    override fun logEvent(tag: String, message: String) {}
}
