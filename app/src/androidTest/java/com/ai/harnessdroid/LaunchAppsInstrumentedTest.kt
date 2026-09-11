package com.ai.harnessdroid

import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates on-device that launch_app resolves real apps (Gmail) by label, by
 * package name and via the capability-based list_compatible_intent_apps tool.
 */
class LaunchAppsInstrumentedTest {

    private fun makeRegistry(): ToolRegistry {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // null InteractionManager: no human-in-the-loop popup, permission auto-approved
        return ToolRegistry(context, null)
    }

    @Test
    fun launchGmailByLabel() = runBlocking {
        val registry = makeRegistry()
        registry.discoverAndBindTools()
        val result = registry.executeTool("launch_app", JSONObject().put("app_name", "Gmail").toString())
        assertTrue("Unexpected result: $result", result.contains("Successfully launched com.google.android.gm"))
    }

    @Test
    fun launchGmailByPackageName() = runBlocking {
        val registry = makeRegistry()
        registry.discoverAndBindTools()
        val result = registry.executeTool(
            "launch_app",
            JSONObject().put("app_name", "com.google.android.gm").toString()
        )
        assertTrue("Unexpected result: $result", result.contains("Successfully launched com.google.android.gm"))
    }

    @Test
    fun unknownAppReturnsAvailableApps() = runBlocking {
        val registry = makeRegistry()
        registry.discoverAndBindTools()
        val result = registry.executeTool("launch_app", JSONObject().put("app_name", "DefinitelyNotAnApp").toString())
        assertTrue("Unexpected result: $result", result.contains("not found"))
        assertTrue("available_apps missing from error: $result", result.contains("available_apps"))
    }

    @Test
    fun compatibleIntentAppsFindsMailHandlers() = runBlocking {
        val registry = makeRegistry()
        registry.discoverAndBindTools()
        val result = registry.executeTool(
            "list_compatible_intent_apps",
            JSONObject().put("capabilities", org.json.JSONArray().put("send_email")).toString()
        )
        assertTrue("Unexpected result: $result", result.contains("com.google.android.gm"))
    }
}
