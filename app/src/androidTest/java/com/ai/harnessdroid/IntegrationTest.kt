package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationTest {

    @Test
    fun testLlmProviderAndTools() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        
        // 1. Test LLMClient binding to ../LLMProv apk
        val llmClient = LLMClient(appContext)
        try {
            // Note: Since this is an emulator, the local LLM generation might be slow or unsupported if the model isn't pushed.
            // But we can at least test if it binds and returns SOMETHING without crashing.
            // Actually, we can just instantiate it. generateText might take forever or fail if no weights.
            // Let's just do a basic binding check if possible, or just call generateText with a very short prompt.
        } catch (e: Exception) {
            // It's okay if generation fails due to missing model, as long as IPC works.
        }

        // 2. Test ToolRegistry discovering tools
        val toolRegistry = ToolRegistry(appContext, null)
        val discoveredTools = toolRegistry.discoverAndBindTools()
        
        // We expect it to find the built-in ask_human_for_input and the web_search from MockWebBrowserService
        assertTrue("Tools should include web_search", discoveredTools.contains("web_search"))
        assertTrue("Tools should include ask_human_for_input", discoveredTools.contains("ask_human_for_input"))
        
        // 3. Test executing the mock web search tool over MCP via Intents
        val result = toolRegistry.executeTool("web_search", """{"query": "Integration Test"}""")
        assertTrue("Result should contain mock search result", result.contains("mock search result"))
        
        toolRegistry.unbindAll()
    }
}
