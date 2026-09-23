package com.ai.harnessdroid.python

import android.content.Context
import android.util.Log
import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Owns the single MicroPython VM: one dedicated thread touches the VM (the GC
 * heap is not thread-safe), a work queue serialises exec/pump jobs, and the
 * idle loop pumps the plan's asyncio services every ~20 ms so things a plan
 * scheduled keep running between tool calls.
 *
 * Heap sizing mirrors the LLM context budget: on first start the engine probes
 * the active model's context window (LLMClient.getContextTokens, the same probe
 * AgentLoop uses for its inline-history budget) and PythonSettings derives the
 * heap from it - big model, big heap; small model, small heap.
 *
 * Cancellation has three escalating levels:
 *  1. the VM's own per-exec deadline (any plan that sleeps or awaits dies),
 *  2. a cooperative cancel request (SIGURG -> deferred raise at a safe point),
 *  3. a hard restart: the VM thread is retired with its heap deliberately
 *     leaked (bounded by MAX_HARD_RESTARTS and logged) and a fresh VM boots.
 */
open class PythonEngine(
    // Nullable only so JVM tests can build an engine without an Android context;
    // production always passes the service. sandboxDir forces non-null on use.
    private val context: Context?,
    private val forensicLogger: ForensicLogger,
    private val toolRegistry: ToolRegistry?,
    /** Probed once per VM start to size the heap like the LLM context budget. */
    private val llmClient: LLMClient? = null,
) {
    companion object {
        private const val TAG = "PythonEngine"
        private const val SANDBOX_DIR = "micropython"
        private const val VM_STACK_BYTES = 256L * 1024 // C stack; Python stack check stays far below
        private const val PUMP_TIMEOUT_MS = 20
        private const val NATIVE_DEATH_GRACE_MS = 3_000L // cancel level 2 before level 3
        private const val MAX_HARD_RESTARTS = 3
    }

    data class ExecResult(val ok: Boolean, val output: String, val error: String?)

    private val queue = LinkedBlockingQueue<Runnable>()
    @Volatile private var vmThread: Thread? = null
    @Volatile private var booted = false
    @Volatile private var booting = false
    @Volatile private var lastHeapKb = 0
    @Volatile private var lastHeapSource = ""
    private var hardRestarts = 0

    open val sandboxDir: File
        get() = File(context!!.filesDir, SANDBOX_DIR)

    /** Booted and accepting execs. */
    val isRunning: Boolean get() = booted

    // ------------------------------------------------------------- lifecycle

    /**
     * Lazily boots the VM (first run_python_plan call, not service start).
     * Returns false when the native lib is unavailable or the bootstrap failed.
     */
    @Synchronized
    open fun start(): Boolean {
        if (booted) return true
        if (booting) return false
        if (!MicropythonNative.ensureLoaded()) {
            Log.w(TAG, "libmicropython.so unavailable; python tool disabled")
            return false
        }
        booting = true
        try {
            sandboxDir.mkdirs()
            MicropythonBridge.toolRegistry = toolRegistry
            MicropythonBridge.forensicLogger = forensicLogger

            // Size the heap like AgentLoop sizes its context budget: probe the
            // ACTIVE model, derive, clamp (PythonSettings.effectiveHeapKb).
            // runBlocking: start() itself is not a suspend call site, and the
            // probe is a single short HTTP/AIDL round trip at boot.
            val contextTokens = try {
                kotlinx.coroutines.runBlocking { llmClient?.getContextTokens() }
            } catch (_: Exception) {
                null
            }
            lastHeapKb = PythonSettings.effectiveHeapKb(contextTokens)
            lastHeapSource = PythonSettings.heapSource(contextTokens)
            forensicLogger.logEvent("PYTHON_START", "heap_kb=$lastHeapKb ($lastHeapSource)")

            spawnVmThread()
            val ok = submit<Boolean> {
                MicropythonNative.nativeStart(sandboxDir.absolutePath.toByteArray(Charsets.UTF_8), lastHeapKb)
            } ?: false
            if (!ok) {
                Log.e(TAG, "python VM bootstrap failed (heap_kb=$lastHeapKb)")
                retireThread()
                return false
            }
            booted = true
            return true
        } finally {
            booting = false
        }
    }

    /** Stops the VM. [wipe] also clears the sandbox directory (Purge & Stop). */
    @Synchronized
    fun stop(wipe: Boolean = false) {
        if (!booted && vmThread == null) return
        booted = false
        if (vmThread != null) {
            // Best-effort cooperative stop; the thread retires either way.
            runCatching { MicropythonNative.nativeRequestCancel() }
            submit<Boolean> {
                MicropythonNative.nativeStop()
                true
            }
            retireThread()
        }
        if (wipe) {
            sandboxDir.listFiles()?.forEach { it.deleteRecursively() }
            forensicLogger.logEvent("PYTHON_PURGED", "sandbox wiped")
        }
    }

    fun wipeSandbox() {
        sandboxDir.listFiles()?.forEach { it.deleteRecursively() }
        forensicLogger.logEvent("PYTHON_PURGED", "sandbox wiped")
    }

    /** VM status for the UI dialog; null when not booted. */
    fun status(): JSONObject? {
        if (!booted) return null
        val native = submit { String(MicropythonNative.nativeStatus(), Charsets.UTF_8) }
            ?: return null
        return try {
            // gc stats come back as the printed JSON of __harness_status__().
            val heapJson = submit {
                String(
                    MicropythonNative.nativeExec(
                        "import json, gc\nprint(json.dumps(__harness_status__()))".toByteArray(Charsets.UTF_8),
                        2000
                    ),
                    Charsets.UTF_8
                )
            }?.let { runCatching { JSONObject(it).optString("output") }.getOrNull() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            JSONObject(native)
                .put("heap_kb", lastHeapKb)
                .put("heap_source", lastHeapSource)
                .put("heap_free", heapJson?.optInt("heap_free", -1) ?: -1)
                .put("heap_alloc", heapJson?.optInt("heap_alloc", -1) ?: -1)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ exec

    /**
     * Runs a plan source to completion (or its deadline). Suspends until the
     * VM thread answers; the outer [timeoutMs] + grace triggers escalation.
     */
    open suspend fun exec(source: String, name: String = "plan", timeoutOverrideMs: Int? = null): ExecResult {
        val timeoutMs = timeoutOverrideMs ?: PythonSettings.effectiveTimeoutMs()
        if (source.length > PythonSettings.effectiveMaxSourceChars()) {
            return ExecResult(false, "", "plan source exceeds ${PythonSettings.effectiveMaxSourceChars()} characters; write it to a file with mode='append' and execute with mode='run'")
        }
        if (!start()) {
            return ExecResult(false, "", "python engine unavailable")
        }
        forensicLogger.logEvent("PYTHON_EXEC", "name=$name chars=${source.length} timeout_ms=$timeoutMs heap_kb=$lastHeapKb")
        val nativeResult = withTimeoutOrNull(timeoutMs + NATIVE_DEATH_GRACE_MS) {
            suspendCancellableCoroutine<ByteArray> { cont ->
                enqueue {
                    val bytes = try {
                        MicropythonNative.nativeExec(source.toByteArray(Charsets.UTF_8), timeoutMs)
                    } catch (e: Throwable) {
                        errorBytes(e.message ?: "native exec crashed")
                    }
                    if (cont.isActive) cont.resume(bytes)
                }
                cont.invokeOnCancellation {
                    // Level 2: cooperative cancel; the VM raises at a safe point.
                    runCatching { MicropythonNative.nativeRequestCancel() }
                }
            }
        }
        if (nativeResult == null) {
            // Level 3: the VM thread is stuck (pure CPU loop the SIGURG could not
            // unwind). Retire thread + leak the heap on purpose; restart bounded.
            forensicLogger.logEvent("PYTHON_TIMEOUT", "name=$name hard restart #$hardRestarts")
            hardRestart()
            return ExecResult(false, "", "plan timed out after ${timeoutMs}ms; the python VM was restarted")
        }
        val result = parseResult(nativeResult)
        forensicLogger.logEvent("PYTHON_RESULT", "name=$name ok=${result.ok} out_chars=${result.output.length}")
        return result
    }

    // ------------------------------------------------------------ internals

    private fun parseResult(bytes: ByteArray): ExecResult {
        val obj = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) {
            return ExecResult(false, "", "unparseable VM result")
        }
        return ExecResult(
            ok = obj.optBoolean("ok", false),
            output = obj.optString("output", ""),
            error = obj.optString("error", "").ifBlank { null },
        )
    }

    private fun errorBytes(message: String): ByteArray =
        ("{\"ok\":false,\"output\":\"\",\"error\":\"${message.replace("\"", "'")}\"}").toByteArray(Charsets.UTF_8)

    /** VM loop: poll the queue; when idle, pump the plan scheduler. */
    private fun spawnVmThread() {
        // java.lang.Thread (not kotlin.concurrent.thread): the latter has no
        // stackSize parameter, and the VM wants a bounded C stack.
        val t = Thread(null, {
            while (true) {
                val job = try { queue.poll(PUMP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (job != null) { job.run(); continue }
                if (booted) runCatching { MicropythonNative.nativePump(PUMP_TIMEOUT_MS) }
            }
        }, "micropython-vm", VM_STACK_BYTES)
        t.start()
        vmThread = t
    }

    private fun retireThread() {
        val t = vmThread
        vmThread = null
        queue.clear()
        t?.interrupt()
        // Deliberately NOT joining the hard-restart path: a thread stuck in
        // native code cannot be interrupted, and the heap it owns is forfeit
        // (bounded leak, see MAX_HARD_RESTARTS).
        if (t?.isAlive == true) {
            runCatching { t.join(500) }
        }
    }

    private fun hardRestart() {
        if (hardRestarts >= MAX_HARD_RESTARTS) {
            Log.e(TAG, "python VM exceeded $MAX_HARD_RESTARTS hard restarts; refusing to start again")
            booted = false
            return
        }
        hardRestarts++
        retireThread() // heap leaks with the retired thread, on purpose and logged
        booted = false
        start()
    }

    /** Runs [body] on the VM thread and waits; null if the thread is gone. */
    private fun <T> submit(body: () -> T): T? {
        val vm = vmThread ?: return null
        if (!vm.isAlive) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        queue.put {
            try { result = body() } catch (e: Throwable) { Log.e(TAG, "vm job failed", e) }
            latch.countDown()
        }
        // Wait on the CALLER's thread: start()/stop() are called from IO coroutines.
        if (!latch.await(10, TimeUnit.SECONDS)) return null
        return result
    }

    /** Enqueue without waiting (used by exec's cancellable continuation). */
    private fun enqueue(job: Runnable) {
        queue.put(job)
    }
}
