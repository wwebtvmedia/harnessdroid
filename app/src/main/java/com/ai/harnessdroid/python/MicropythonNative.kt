package com.ai.harnessdroid.python

/**
 * Thin JNI surface of libmicropython.so. Every entry point must be called on
 * the single VM thread PythonEngine owns (the MicroPython GC heap is not
 * thread-safe); strings cross as UTF-8 byte arrays, not jstrings.
 *
 * [isAvailable] is false on JVM unit-test runs where the .so cannot load:
 * callers degrade to a clean "unavailable" answer instead of crashing.
 */
object MicropythonNative {
    private var loadAttempted = false

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Idempotent; returns false when libmicropython.so is not present. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadAttempted) return isAvailable
        loadAttempted = true
        try {
            System.loadLibrary("micropython")
            isAvailable = true
        } catch (_: UnsatisfiedLinkError) {
            isAvailable = false
        }
        return isAvailable
    }

    /** Boots the VM on the calling thread; returns false on bootstrap failure. */
    @JvmStatic external fun nativeStart(sandboxDir: ByteArray, heapKb: Int): Boolean

    /** Runs `source` to its deadline; returns {"ok":…,"output":…,"error":…} JSON bytes. */
    @JvmStatic external fun nativeExec(source: ByteArray, timeoutMs: Int): ByteArray

    /** One asyncio tick for scheduled services; call only between execs. */
    @JvmStatic external fun nativePump(timeoutMs: Int)

    /** {"booted":…,"uptime_ms":…,"exec_count":…,"last_error":…} JSON bytes. */
    @JvmStatic external fun nativeStatus(): ByteArray

    /** Cooperative cancel: deadline-style raise at the next safe point. */
    @JvmStatic external fun nativeRequestCancel()

    /** Tears the VM down on the calling thread (thread retires afterwards). */
    @JvmStatic external fun nativeStop()
}
