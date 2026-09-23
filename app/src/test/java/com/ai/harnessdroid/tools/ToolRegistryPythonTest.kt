package com.ai.harnessdroid.tools

import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.python.PythonEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the run_python_plan tool surface: gating (kill switch, missing
 * engine), the save/append file modes that let a plan longer than one tool call
 * be composed, and the sandbox path guard. A fake engine (open start/sandboxDir)
 * stands in for the native VM, which cannot load on the JVM.
 */
class ToolRegistryPythonTest {

    /** Fake engine: boots "successfully" without any native code. */
    private class FakeEngine(tmp: File) : PythonEngine(null as android.content.Context?, ForensicLogger(null), null) {
        override val sandboxDir: File = File(tmp, "sandbox").apply { mkdirs() }
        override fun start(): Boolean = true
        var lastSource: String? = null
        override suspend fun exec(source: String, name: String, timeoutOverrideMs: Int?): ExecResult {
            lastSource = source
            return ExecResult(true, "fake-ok:$source", null)
        }
    }

    private fun registry(engine: PythonEngine?): ToolRegistry =
        ToolRegistry(null as android.content.Context?, null).also { it.pythonEngine = engine }

    private suspend fun run(reg: ToolRegistry, args: String): JSONObject =
        JSONObject(reg.executeTool("run_python_plan", args))

    private fun tmpDir(): File = File(System.getProperty("java.io.tmpdir"), "hd_py_tools_test")

    @Test
    fun withoutEngineTheToolAnswersUnavailable() = runBlocking {
        val out = run(registry(null), "{\"code\":\"print(1)\"}")
        // House error convention: {"error": ...} without an ok key.
        assertTrue(out.getString("error").contains("unavailable"))
    }

    @Test
    fun killSwitchDisablesTheTool() = runBlocking {
        val prev = System.getProperty("HARNESS_PYTHON_ENABLED")
        try {
            System.setProperty("HARNESS_PYTHON_ENABLED", "0")
            val out = run(registry(FakeEngine(tmpDir())), "{\"code\":\"print(1)\"}")
            assertTrue(out.getString("error").contains("disabled"))
        } finally {
            if (prev == null) System.clearProperty("HARNESS_PYTHON_ENABLED") else System.setProperty("HARNESS_PYTHON_ENABLED", prev)
        }
    }

    @Test
    fun saveThenRunComposesAPlanAcrossCalls() = runBlocking {
        val eng = FakeEngine(tmpDir())
        val reg = registry(eng)

        val saved = run(reg, """{"mode":"save","file":"plans/p.py","code":"print('part1')"}""")
        assertTrue(saved.optBoolean("ok"))
        val appended = run(reg, """{"mode":"append","file":"plans/p.py","code":"print('part2')"}""")
        assertTrue(appended.optBoolean("ok"))

        val executed = run(reg, """{"mode":"run","file":"plans/p.py"}""")
        assertTrue(executed.optBoolean("ok"))
        assertTrue(eng.lastSource!!.contains("part1"))
        assertTrue(eng.lastSource!!.contains("part2"))
    }

    @Test
    fun pathGuardRejectsEscapesAndAbsolutePaths() = runBlocking {
        val reg = registry(FakeEngine(tmpDir()))
        for (bad in listOf("../evil.py", "/etc/passwd", "..")) {
            val out = run(reg, """{"mode":"save","file":"$bad","code":"x=1"}""")
            assertFalse("file=$bad must be rejected", out.optBoolean("ok"))
            assertTrue(out.getString("error").contains("file"))
        }
    }

    @Test
    fun saveRequiresCodeAndFile() = runBlocking {
        val reg = registry(FakeEngine(tmpDir()))
        val noCode = run(reg, """{"mode":"save","file":"a.py","code":""}""")
        assertFalse(noCode.optBoolean("ok"))
        val noFile = run(reg, """{"mode":"save","code":"x=1"}""")
        assertFalse(noFile.optBoolean("ok"))
    }

    @Test
    fun unknownModeIsRejected() = runBlocking {
        val out = run(registry(FakeEngine(tmpDir())), """{"mode":"explode","code":"x=1"}""")
        assertFalse(out.optBoolean("ok"))
        assertTrue(out.getString("error").contains("mode"))
    }

    @Test
    fun inlineRunReachesTheEngine() = runBlocking {
        val eng = FakeEngine(tmpDir())
        val out = run(registry(eng), """{"code":"print(40+2)","timeout_seconds":2}""")
        assertTrue(out.optBoolean("ok"))
        assertEquals("print(40+2)", eng.lastSource)
    }

    @Test
    fun runOfMissingPlanFileIsExplained() = runBlocking {
        val out = run(registry(FakeEngine(tmpDir())), """{"mode":"run","file":"plans/never.py"}""")
        assertFalse(out.optBoolean("ok"))
        assertTrue(out.getString("error").contains("does not exist"))
    }
}
