package com.tree4five.harness.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Non-Regression (non-reg) tests for the Agent Loop.
 * Ensures the code is easy to review and mechanically sound.
 */
class AgentLoopTest {

    @Test
    fun testLoopStopsWhenMaxTurnsReached() = runBlocking {
        // Mock LLM that always returns a tool call (infinite loop simulation)
        val mockLlmClient = MockLLMClient(alwaysCallTool = true)
        val mockToolRegistry = MockToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        
        val result = agentLoop.runTask("Do something", maxTurns = 3)
        
        // Assert non-regression on loop bounds
        assertTrue(result.contains("Maximum turns reached"))
        assertEquals(3, mockToolRegistry.executionCount)
    }

    @Test
    fun testLoopSuccessfullyExecutesToolAndReturnsFinalAnswer() = runBlocking {
        val mockLlmClient = MockLLMClient(alwaysCallTool = false)
        val mockToolRegistry = MockToolRegistry()
        val mockPersistence = MockSessionPersistence()
        val logger = ForensicLoggerMock()

        val agentLoop = AgentLoop(mockLlmClient, mockToolRegistry, mockPersistence, logger)
        
        val result = agentLoop.runTask("Get the weather", maxTurns = 5)
        
        // Assert the LLM's final answer is returned
        assertEquals("Final Answer", result)
    }
}

// --- Mocks to make tests completely local and fast to review ---

class MockLLMClient(private val alwaysCallTool: Boolean) : com.tree4five.harness.llm.LLMClient(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext as android.content.Context?) {
    private var turn = 0
    override suspend fun generateText(prompt: String): String {
        turn++
        return if (alwaysCallTool || turn == 1) {
            """{"tool_call": {"name": "mockTool", "arguments": {}}}"""
        } else {
            "Final Answer"
        }
    }
}

class MockToolRegistry : com.tree4five.harness.tools.ToolRegistry(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, null) {
    var executionCount = 0
    override suspend fun discoverAndBindTools(): String = "[]"
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
