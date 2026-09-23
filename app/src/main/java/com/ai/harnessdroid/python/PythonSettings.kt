package com.ai.harnessdroid.python

/**
 * Configuration of the embedded MicroPython VM.
 *
 * The VM's SIZE follows the same philosophy as the inline-context budget of
 * AgentLoop: it is DERIVED from the active model's context window
 * (LLMClient.getContextTokens(), the same probe AgentLoop uses), clamped to
 * safe bounds, and can be pinned with an explicit override knob. A plan lives
 * inside the prompt the model wrote, so a model with a small context window
 * also gets a small Python heap - the two sizes never drift apart.
 *
 * Knobs (system property or env var, house style):
 *   HARNESS_PYTHON_HEAP_KB          explicit heap override (16..1024)
 *   HARNESS_PYTHON_TIMEOUT_MS       per-exec wall clock (default 15000)
 *   HARNESS_PYTHON_MAX_SOURCE_CHARS plan size cap (default 20000)
 *   HARNESS_PYTHON_ENABLED          "0" kills the tool; everything else keeps it
 */
object PythonSettings {
    // Heap bounds in KB. 64 KB is the floor the interpreter + bootstrap + one
    // asyncio service need; beyond 1 MB the GC simply amortises badly.
    const val HEAP_MIN_KB = 64
    const val HEAP_MAX_KB = 1024

    /** Heap KB per token of the model's context window: 2048 tokens -> 64 KB,
     *  8192 -> 256 KB, 16384+ -> the 1 MB cap. Bigger context windows come with
     *  longer plans; this keeps the two budgets proportional. */
    const val HEAP_KB_PER_CONTEXT_TOKEN = 32

    /** Heap used when no override exists and the context probe fails. */
    const val HEAP_DEFAULT_KB = HEAP_MIN_KB

    const val TIMEOUT_DEFAULT_MS = 15_000
    const val TIMEOUT_MIN_MS = 1_000
    const val TIMEOUT_MAX_MS = 120_000

    const val MAX_SOURCE_CHARS_DEFAULT = 20_000

    private val heapKbOverride: Int?
        get() = (System.getProperty("HARNESS_PYTHON_HEAP_KB")
            ?: System.getenv("HARNESS_PYTHON_HEAP_KB"))?.toIntOrNull()

    private val timeoutMsOverride: Int?
        get() = (System.getProperty("HARNESS_PYTHON_TIMEOUT_MS")
            ?: System.getenv("HARNESS_PYTHON_TIMEOUT_MS"))?.toIntOrNull()

    private val maxSourceCharsOverride: Int?
        get() = (System.getProperty("HARNESS_PYTHON_MAX_SOURCE_CHARS")
            ?: System.getenv("HARNESS_PYTHON_MAX_SOURCE_CHARS"))?.toIntOrNull()

    val enabled: Boolean
        get() = (System.getProperty("HARNESS_PYTHON_ENABLED")
            ?: System.getenv("HARNESS_PYTHON_ENABLED")) != "0"

    /**
     * VM heap for a given context window, in KB. Pure so JVM tests can pin the
     * mapping; the engine feeds it the probed context size.
     */
    fun heapKbForContext(contextTokens: Int): Int =
        (contextTokens.coerceAtLeast(0) * HEAP_KB_PER_CONTEXT_TOKEN / 1024)
            .coerceIn(HEAP_MIN_KB, HEAP_MAX_KB)

    /**
     * The effective heap: the explicit override wins, otherwise the size tracks
     * the probed context window ([contextTokens] null or <= 0 -> default).
     */
    fun effectiveHeapKb(contextTokens: Int?): Int {
        val override = heapKbOverride?.coerceIn(16, 4096)
        if (override != null) return override
        if (contextTokens == null || contextTokens <= 0) return HEAP_DEFAULT_KB
        return heapKbForContext(contextTokens)
    }

    /** Where the effective heap came from, for the status dialog / logs. */
    fun heapSource(contextTokens: Int?): String =
        if (heapKbOverride != null) "override" else "context(${contextTokens ?: "probe-failed"})"

    fun effectiveTimeoutMs(): Int =
        (timeoutMsOverride ?: TIMEOUT_DEFAULT_MS).coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)

    fun effectiveMaxSourceChars(): Int =
        (maxSourceCharsOverride ?: MAX_SOURCE_CHARS_DEFAULT).coerceIn(1000, 200_000)
}
