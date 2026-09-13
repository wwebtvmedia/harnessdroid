package com.ai.harnessdroid.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the tool-result safety guards: the MAX cap on one tool result
 * (HARNESS_MAX_TOOL_RESULT_CHARS), offset/limit paging args, and delegate_task
 * gating. The registry is built with a null context so every path exercised here
 * answers without touching Android services.
 */
class ToolResultLimitsTest {

    private fun registry(
        allowDelegation: Boolean = true,
        delegateHandler: (suspend (String, String) -> String)? = null
    ): ToolRegistry {
        val reg = ToolRegistry(null as android.content.Context?, null, allowDelegation)
        reg.delegateHandler = delegateHandler
        return reg
    }

    // ---- Truncation ----

    @Test fun `oversized json result is surgically truncated with a note`() {
        // The cap is read at construction time, so it must be set first.
        System.setProperty("HARNESS_MAX_TOOL_RESULT_CHARS", "1000")
        try {
            val reg = registry()
            val dump = (1..500).joinToString("\n") { "Mail row $it from sender$it@example.com [0,$it][720,${it + 20}]" }
            val out = reg.enforceToolResultLimit(
                "read_screen",
                JSONObject().put("screen", dump).put("hint", "short").toString()
            )
            val parsed = JSONObject(out)
            assertTrue(out.length < 1500)
            assertTrue("payload must stay valid JSON", parsed.getBoolean("truncated"))
            assertTrue(parsed.getString("screen").endsWith("…"))
            assertEquals("sibling fields survive", "short", parsed.getString("hint"))
            assertTrue(parsed.getString("truncation_note").contains("Output truncated"))
        } finally {
            System.clearProperty("HARNESS_MAX_TOOL_RESULT_CHARS")
        }
    }

    @Test fun `oversized non-json result is hard cut`() {
        System.setProperty("HARNESS_MAX_TOOL_RESULT_CHARS", "500")
        try {
            val reg = registry()
            val out = reg.enforceToolResultLimit("mcp_tool", "x".repeat(5000))
            assertTrue(out.length < 1000)
            assertTrue(out.contains("Output truncated"))
        } finally {
            System.clearProperty("HARNESS_MAX_TOOL_RESULT_CHARS")
        }
    }

    @Test fun `small result passes through byte for byte`() {
        val reg = registry()
        val input = JSONObject().put("result", "ok").toString()
        assertEquals(input, reg.enforceToolResultLimit("get_os_info", input))
    }

    // ---- Pagination ----

    @Test fun `list_installed_apps without a context answers cleanly`() = runBlocking {
        val out = registry().executeTool("list_installed_apps", """{"offset":0,"limit":2}""")
        val parsed = JSONObject(out)
        assertTrue(parsed.optString("result").contains("None"))
        assertFalse(parsed.has("returned"))
        assertFalse(parsed.has("next_offset"))
    }

    @Test fun `list_compatible_intent_apps tolerates offset and limit without a context`() = runBlocking {
        val out = registry().executeTool(
            "list_compatible_intent_apps",
            """{"capabilities":["read_mail"],"offset":5,"limit":10}"""
        )
        val parsed = JSONObject(out)
        assertTrue(parsed.optString("result").isNotBlank())
        assertFalse(parsed.has("next_offset"))
    }

    // ---- Delegation ----

    @Test fun `delegate_task routes to the handler and returns its payload`() = runBlocking {
        val reg = registry(delegateHandler = { task, agentType ->
            JSONObject().put("result", "sub did: $task").put("subagent", agentType).toString()
        })
        val out = reg.executeTool("delegate_task", """{"task":"open gmail","agent_type":"navigator"}""")
        val parsed = JSONObject(out)
        assertEquals("sub did: open gmail", parsed.getString("result"))
        assertEquals("navigator", parsed.getString("subagent"))
    }

    @Test fun `delegate_task without a handler returns a recoverable error`() = runBlocking {
        val out = registry().executeTool("delegate_task", """{"task":"open gmail"}""")
        assertTrue(JSONObject(out).getString("error").contains("not available"))
    }

    @Test fun `delegate_task without a task returns an argument error`() = runBlocking {
        val out = registry(delegateHandler = { task, _ ->
            JSONObject().put("result", task).toString()
        }).executeTool("delegate_task", "{}")
        assertTrue(JSONObject(out).getString("error").contains("requires 'task'"))
    }

    @Test fun `handler results also pass the size cap`() {
        System.setProperty("HARNESS_MAX_TOOL_RESULT_CHARS", "500")
        try {
            val reg = registry(delegateHandler = { _, _ -> JSONObject().put("result", "y".repeat(5000)).toString() })
            val out = reg.enforceToolResultLimit("delegate_task", "y".repeat(5000))
            assertTrue(out.length < 1000)
            assertTrue(out.contains("Output truncated"))
        } finally {
            System.clearProperty("HARNESS_MAX_TOOL_RESULT_CHARS")
        }
    }

    // ---- Schema gating ----

    @Test fun `schema exposes delegate_task when delegation is allowed`() = runBlocking {
        assertTrue(registry().discoverAndBindTools().contains("delegate_task"))
    }

    @Test fun `schema hides delegate_task for sub-agent registries`() = runBlocking {
        val schemas = registry(allowDelegation = false).discoverAndBindTools()
        assertFalse(schemas.contains("delegate_task"))
        // The rest of the built-in toolset stays available to the sub-agent.
        assertTrue(schemas.contains("read_screen"))
    }
}
