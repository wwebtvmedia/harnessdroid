package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.core.AgentLoop
import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.core.SessionPersistence
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class RealLLME2ETest {

    @Test
    fun testRealLLMAgentLoop() {
        runBlocking {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val llmClient = LLMClient(appContext)
            val toolRegistry = ToolRegistry(appContext, null)
            val persistence = SessionPersistence(appContext, "real_llm_e2e")
            val logger = object : ForensicLogger(appContext) {
                override fun logEvent(tag: String, message: String) {
                    Log.d("RealLLME2E", "[$tag] $message")
                    println("[$tag] $message")
                }
            }
            
            val agent = AgentLoop(llmClient, toolRegistry, persistence, logger)
            
            try {
                Log.d("RealLLME2E", "Starting real LLM generation test")
                val result = agent.runTask("please list all tools accessible", maxTurns = 3)
                Log.d("RealLLME2E", "Agent Loop Result: $result")
                assertTrue(result.isNotEmpty())
            } catch(e: Exception) {
                Log.e("RealLLME2E", "Failed real generation", e)
            }
        }
    }
}
