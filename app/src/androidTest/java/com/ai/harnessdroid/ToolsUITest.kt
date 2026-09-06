package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class ToolsUITest {
    @Test
    fun testGetAvailableTools() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = ToolRegistry(appContext, null)
        val toolsJson = registry.discoverAndBindTools()
        Log.d("ToolsUITest", "Tools JSON: $toolsJson")
        println("Tools JSON: $toolsJson")
        val array = org.json.JSONArray(toolsJson)
        assertTrue("Should have at least 4 built-in tools", array.length() >= 4)
    }
}
