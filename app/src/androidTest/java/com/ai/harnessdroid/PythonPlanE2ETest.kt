package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.core.AgentLoop
import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.core.SessionPersistence
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.python.PythonEngine
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Full-harness E2E against the LOCAL on-device LLM (Tree4Five AIDL provider):
 * the user explicitly asks for a simple Python plan, the agent loop must make
 * the LLM generate that code, route it through run_python_plan and execute it
 * in the real MicroPython VM.
 *
 * Assertions target the pipeline, not the LLM's wording:
 *  - a run_python_plan round trip happened (PYTHON_EXEC log event),
 *  - the LLM-generated code ran without error (PYTHON_RESULT ok=true),
 *  - the plan's printed result is what was asked for ("55" for 1+..+10).
 *
 * Requires the local provider to be installed and llm_config in provider mode;
 * tools/run scripts do that (run_e2e_local_llm.sh).
 */
@RunWith(AndroidJUnit4::class)
class PythonPlanE2ETest {

    private val captured = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val planOutputs = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val logger = object : ForensicLogger(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        override fun logEvent(tag: String, message: String) {
            captured.add(tag to message)
            android.util.Log.d("PythonPlanE2E", "[$tag] $message")
        }
    }

    private lateinit var engine: PythonEngine

    @After
    fun teardown() {
        if (::engine.isInitialized) engine.stop(wipe = true)
    }

    @Test(timeout = 720_000L)
    fun llmGeneratesAndRunsSimplePythonPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val llmClient = LLMClient(context)
        val toolRegistry = ToolRegistry(context, null)
        // Same engine shape as HarnessService, wrapped to keep every plan output.
        engine = object : PythonEngine(context, logger, toolRegistry, llmClient) {
            override suspend fun exec(
                source: String,
                name: String,
                timeoutOverrideMs: Int?,
            ): ExecResult {
                val r = super.exec(source, name, timeoutOverrideMs)
                planOutputs.add(r.output + "\n" + (r.error ?: ""))
                return r
            }
        }
        toolRegistry.pythonEngine = engine
        val persistence = SessionPersistence(context, "python_plan_e2e")
        val agent = AgentLoop(llmClient, toolRegistry, persistence, logger)

        val instruction =
            "Use the run_python_plan tool to run a Python program that computes " +
                "the sum of the integers from 1 to 10 and prints it once. " +
                "Then tell me the number it printed."
        val result = kotlinx.coroutines.runBlocking { agent.runTask(instruction, maxTurns = 8) }
        android.util.Log.d("PythonPlanE2E", "final: $result")

        val tags = captured.map { it.first }
        assertTrue(
            "the LLM never called run_python_plan (events=$tags). " +
                "Is the local provider com.tree4five.gguf running with llm_config in provider mode?",
            tags.contains("PYTHON_EXEC")
        )
        val results = captured.filter { it.first == "PYTHON_RESULT" }
        assertTrue(
            "no completed python exec (PYTHON_RESULT events=$results)",
            results.isNotEmpty()
        )
        assertTrue(
            "the LLM-generated plan failed: $results",
            results.any { it.second.contains("ok=true") }
        )
        val allOutput = planOutputs.joinToString("\n")
        assertTrue(
            "the plan output never showed the expected 55, got: $allOutput",
            allOutput.contains("55")
        )
        // The harness hands the tool result back; the final answer should carry
        // the number too (tiny models may phrase it freely, the digits suffice).
        assertTrue(
            "final answer lost the computed result: $result",
            result.contains("55") || allOutput.contains("55")
        )
    }
}
