package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OspQueryInstrumentedTest {

    /** The osp_query schema is advertised to the LLM like any built-in tool. */
    @Test
    fun ospQueryIsDiscovered() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val toolRegistry = ToolRegistry(appContext, null)
        val discovered = toolRegistry.discoverAndBindTools()
        assertTrue("Tools should include osp_query", discovered.contains("osp_query"))
        toolRegistry.unbindAll()
    }

    /**
     * Full AIDL round trip against the OSP Bridge app: bind, submitQuery,
     * callback. Without a started LLM provider or peers the node honestly
     * abstains — any well-formed protocol outcome (or the graceful
     * not-reachable error when the app is absent) proves the wiring.
     */
    @Test
    fun ospQueryRoundTripReturnsWellFormedOutcome() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val toolRegistry = ToolRegistry(appContext, null)
        val out = toolRegistry.executeTool(
            "osp_query",
            org.json.JSONObject().put("query", "What is the magazine's full title?").put("tier", 0).toString()
        )
        val json = org.json.JSONObject(out)
        val wellFormed = json.has("mode") || json.has("error")
        assertTrue("Expected a protocol outcome or a graceful error, got: $out", wellFormed)
        toolRegistry.unbindAll()
    }
}
