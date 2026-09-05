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
}

class MockLLMClient(private val infiniteTool: Boolean) : com.ai.harnessdroid.llm.LLMClient(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    private var state = 0
    override suspend fun generateText(prompt: String): String {
        return if (prompt.contains("Which tool do you want to use next?")) {
            if (infiniteTool) {
                "mockTool"
            } else {
                if (state == 0) {
                    state = 1
                    "mockTool"
                } else {
                    "NONE"
                }
            }
        } else if (prompt.contains("JSON object containing the arguments")) {
            "{ \"testArg\": \"val\" }"
        } else {
            "Final Answer"
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
        return JSONArray().put(tool).toString()
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
