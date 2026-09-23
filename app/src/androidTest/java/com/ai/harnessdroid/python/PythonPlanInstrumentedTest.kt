package com.ai.harnessdroid.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The MicroPython VM exercised for real on-device: pure compute, the sandbox
 * VFS (persistence across execs and its escape guard), the asyncio scheduler
 * running a plan service between tool calls, the per-exec deadline with a hard
 * restart afterwards, and the purge that backs "Purge & Stop". Each test boots
 * its own engine and leaves nothing behind (stop(wipe=true)).
 */
@RunWith(AndroidJUnit4::class)
class PythonPlanInstrumentedTest {

    private lateinit var engine: PythonEngine

    /** Sandbox root as seen from Android: <filesDir>/micropython == "/" in the VM. */
    private val sandboxRoot: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "micropython"
        )

    @Before
    fun bootVm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Real context everywhere: ForensicLogger(null) writes to /tmp, absent on Android.
        engine = PythonEngine(context, ForensicLogger(context), ToolRegistry(context, null), null)
        assertTrue("the VM must boot on-device", engine.start())
    }

    @After
    fun teardownVm() {
        engine.stop(wipe = true)
    }

    private fun sandboxFile(vmPath: String): File {
        val rel = vmPath.trimStart('/')
        return File(sandboxRoot, rel)
    }

    /** Polls the Android side until the VM-written file appears (the pump runs it). */
    private fun awaitSandboxFile(vmPath: String, timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            sandboxFile(vmPath).takeIf { it.exists() }?.let { return it }
            Thread.sleep(150)
        }
        return null
    }

    @Test
    fun pureComputationRunsAndPrints() = runBlocking {
        val r = engine.exec("print(1+1)\nprint('x' * 3)")
        assertTrue("ok expected, error=${r.error}", r.ok)
        assertTrue(r.output.contains("2"))
        assertTrue(r.output.contains("xxx"))
    }

    @Test
    fun filesystemPersistsAcrossExecs() = runBlocking {
        val w = engine.exec(
            "import os\n" +
                "try:\n    os.mkdir('/data')\nexcept OSError:\n    pass\n" +
                "with open('/data/liste.txt', 'w') as f:\n" +
                "    f.write('alpha\\nbeta\\ngamma')\n" +
                "print('written')"
        )
        assertTrue("write failed: ${w.error}", w.ok)

        // The file is real on the Android side, under the sandbox root.
        val onDevice = sandboxFile("/data/liste.txt")
        assertTrue("file must exist on device", onDevice.exists())
        assertTrue(onDevice.readText().contains("beta"))

        // And the VM's own VFS sees it again in a fresh exec.
        val r = engine.exec("print(len(open('/data/liste.txt').read()))")
        assertTrue("read-back failed: ${r.error}", r.ok)
        assertTrue(r.output.trim().endsWith("16"))
    }

    @Test
    fun sandboxEscapeIsRejected() = runBlocking {
        // The bootstrap wraps open/os so '..' cannot walk out of the chroot.
        val r = engine.exec(
            "try:\n" +
                "    open('../outside.txt', 'w')\n" +
                "    print('ESCAPED')\n" +
                "except Exception as e:\n" +
                "    print('refused:', e)"
        )
        assertTrue(r.ok)
        val combined = r.output + (r.error ?: "")
        assertFalse("the escape must not succeed", combined.contains("ESCAPED"))
        assertTrue(combined.contains("path escapes the python sandbox"))
        assertFalse(sandboxFile("../outside.txt").exists())
    }

    @Test
    fun scheduledServiceRunsBetweenToolCalls() = runBlocking {
        // Arm a service: every second it calls back into the harness (a real
        // tool round trip through the JNI bridge) and logs the result. The plan
        // returns immediately; the VM's idle pump keeps the service alive.
        // call_tool is synchronous - a bare dict, not a coroutine to await
        // (awaiting it would silently strand the task; the bootstrap's
        // _ToolResult makes that mistake harmless anyway).
        val arm = engine.exec(
            "import os\n" +
                "try:\n    os.mkdir('/logs')\nexcept OSError:\n    pass\n" +
                "async def beat():\n" +
                "    try:\n" +
                "        r = call_tool('get_os_info')\n" +
                "        with open('/logs/beat.txt', 'w') as f:\n" +
                "            f.write(str(r.get('result', r)))\n" +
                "    except Exception as e:\n" +
                "        with open('/logs/beat_err.txt', 'w') as f:\n" +
                "            f.write(type(e).__name__ + ': ' + str(e))\n" +
                "every(1, beat)\n" +
                "print('service armed')"
        )
        assertTrue("arm failed: ${arm.error}", arm.ok)
        assertTrue(arm.output.contains("service armed"))

        // The service fires while Kotlin-side nothing is running.
        val file = awaitSandboxFile("/logs/beat.txt", timeoutMs = 8_000)
        val errFile = sandboxFile("/logs/beat_err.txt")
        assertTrue(
            "the scheduled service never ran (err=${if (errFile.exists()) errFile.readText() else "none"})",
            file != null
        )
        val content = file!!.readText()
        assertTrue("beat should carry the tool result, got: $content", content.contains("Android"))

        // And the VM reads it back through its own VFS.
        val r = engine.exec("print(len(open('/logs/beat.txt').read()) > 0)")
        assertTrue(r.ok)
        assertTrue(r.output.contains("True"))
    }

    @Test
    fun runawaySpinIsKilledAndVmRestarts() = runBlocking {
        val r = engine.exec("while True:\n    pass", name = "spin", timeoutOverrideMs = 1_500)
        assertFalse("the spin must be killed", r.ok)
        assertTrue(r.error!!.contains("timed out"))

        // The hard restart must yield a usable, freshly-bootstrapped VM.
        val back = engine.exec("print('back')\nprint(6*7)")
        assertTrue("VM unusable after restart: ${back.error}", back.ok)
        assertTrue(back.output.contains("back"))
        assertTrue(back.output.contains("42"))
    }

    @Test
    fun wipeRemovesSandboxContentsOnDevice() = runBlocking {
        val w = engine.exec(
            "with open('/leftover.txt', 'w') as f:\n    f.write('bye')"
        )
        assertTrue(w.ok)
        val f = sandboxFile("/leftover.txt")
        assertTrue(f.exists())

        engine.wipeSandbox()

        assertFalse("the purge must clear the sandbox", f.exists())
        assertTrue(
            "sandbox must be empty",
            sandboxRoot.listFiles()?.all { it.isHidden } ?: true
        )
    }
}
