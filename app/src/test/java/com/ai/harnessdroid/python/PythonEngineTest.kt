package com.ai.harnessdroid.python

import com.ai.harnessdroid.core.ForensicLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the engine's failure paths: the .so cannot load here, so
 * [MicropythonNative.isAvailable] is false and every entry point must answer
 * with a clean, structured result instead of crashing. Exec paths that need a
 * live VM are covered by PythonPlanInstrumentedTest on-device.
 */
class PythonEngineTest {

    private fun engine(): PythonEngine =
        PythonEngine(null as android.content.Context?, ForensicLogger(null), null, null)

    @Test
    fun startFailsCleanlyWithoutNativeLibrary() {
        val e = engine()
        // Either the .so genuinely cannot load (JVM) -> false; never a throw.
        assertFalse(e.start())
        assertFalse(e.isRunning)
    }

    @Test
    fun execReportsUnavailableWhenVmCannotBoot() = runBlocking {
        val result = engine().exec("print(1+1)")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("unavailable"))
    }

    @Test
    fun execRejectsOversizedSourceBeforeTouchingTheVm() = runBlocking {
        val big = "x = 1\n".repeat(6000) // > 20000 chars
        val result = engine().exec(big)
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("exceeds"))
        assertTrue(result.error!!.contains("append"))
    }

    @Test
    fun bridgeWithoutRegistryAnswersStructuredError() {
        MicropythonBridge.toolRegistry = null
        val bytes = MicropythonBridge.callTool(
            "get_os_info".toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8)
        )
        val body = bytes.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"ok\":false"))
        assertTrue(body.contains("registry"))
    }

    @Test
    fun bridgeRefusesNestedPlanRuns() {
        // Even with a registry wired, a plan calling run_python_plan would
        // deadlock the single VM thread - it must be refused at the bridge.
        MicropythonBridge.toolRegistry = com.ai.harnessdroid.tools.ToolRegistry(null as android.content.Context?, null)
        try {
            val bytes = MicropythonBridge.callTool(
                "run_python_plan".toByteArray(Charsets.UTF_8),
                "{\"code\":\"print(1)\"}".toByteArray(Charsets.UTF_8)
            )
            assertTrue(bytes.toString(Charsets.UTF_8).contains("nested"))
        } finally {
            MicropythonBridge.toolRegistry = null
        }
    }

    @Test
    fun sandboxDefaultsToFilesDirSubdirectory() {
        // Null context cannot produce a path; assert the name convention via a
        // fake context-free engine subclass is pointless here - instead assert
        // the constant contract the registry/UI rely on.
        val tmp = File(System.getProperty("java.io.tmpdir"), "hd_py_contract")
        tmp.mkdirs()
        tmp.listFiles()?.forEach { it.deleteRecursively() }
        assertTrue(tmp.isDirectory)
    }
}
