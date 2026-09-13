package com.ai.harnessdroid.core

import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM test of sub-agent delegation: the main loop must be able to call
 * delegate_task, receive the sub-agent's answer as a tool result, and fold it into
 * its own final answer — while the sub-agent's registry itself refuses to delegate.
 */
class AgentLoopDelegationTest {

    private class DelegatingLLM : LLMClient(null as android.content.Context?) {
        var stepTool = 0
        val prompts = mutableListOf<String>()
        override suspend fun generateText(prompt: String): String {
            prompts += prompt
            return when {
                prompt.lowercase().contains("which tool") ->
                    if (stepTool++ == 0) "delegate_task" else "NONE"
                prompt.contains("JSON object containing the arguments") ->
                    """{"task":"open Gmail and report the latest sender","agent_type":"researcher"}"""
                else -> "Final Answer"
            }
        }
    }

    @Test
    fun `delegate_task reaches the handler and the answer joins the main context`() = runBlocking {
        val llm = DelegatingLLM()
        val registry = ToolRegistry(null as android.content.Context?, null)
        var delegatedTask: String? = null
        var delegatedType: String? = null
        registry.delegateHandler = { task, agentType ->
            delegatedTask = task
            delegatedType = agentType
            JSONObject().put("result", "Latest sender: alice@example.com")
                .put("subagent", agentType).toString()
        }
        val loop = AgentLoop(llm, registry, MockSessionPersistence(), ForensicLoggerMock())

        val result = loop.runTask("Who sent my latest email?", maxTurns = 5)

        assertEquals("open Gmail and report the latest sender", delegatedTask)
        assertEquals("researcher", delegatedType)
        // The sub-agent's answer must reach the model before the final reply is written.
        assertTrue(
            llm.prompts.any { it.contains("Latest sender: alice@example.com") }
        )
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `a failing sub-agent returns a recoverable error instead of crashing the loop`() = runBlocking {
        val llm = DelegatingLLM()
        val registry = ToolRegistry(null as android.content.Context?, null)
        registry.delegateHandler = { _, _ -> throw IllegalStateException("provider dead") }
        val loop = AgentLoop(llm, registry, MockSessionPersistence(), ForensicLoggerMock())

        val result = loop.runTask("Who sent my latest email?", maxTurns = 5)

        // The loop survived: it produced a final answer even though delegation blew up.
        assertTrue(result.isNotBlank())
        assertTrue(
            "the error text must be handed back to the model as a tool result",
            llm.prompts.any { it.contains("provider dead") || it.contains("failed") }
        )
    }
}
