package com.ai.harnessdroid.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the python VM size policy: the heap tracks the ACTIVE model's
 * context window (the same probe AgentLoop uses for its inline budget), with
 * clamps and an explicit override knob.
 */
class PythonSettingsTest {

    @Test
    fun heapTracksContextWindow() {
        // 2048 tokens (small local GGUF default) -> the 64 KB floor.
        assertEquals(64, PythonSettings.heapKbForContext(2048))
        // Proportional in between: 32 KB of heap per KB of tokens.
        assertEquals(256, PythonSettings.heapKbForContext(8192))
        // Big remote models land on the 1 MB cap.
        assertEquals(1024, PythonSettings.heapKbForContext(32768))
        assertEquals(1024, PythonSettings.heapKbForContext(200000))
    }

    @Test
    fun heapNeverBelowFloorEvenForTinyContexts() {
        assertEquals(64, PythonSettings.heapKbForContext(512))
        assertEquals(64, PythonSettings.heapKbForContext(0))
    }

    @Test
    fun probeFailureFallsBackToDefault() {
        // null / non-positive probe -> the default (floor) heap.
        assertEquals(PythonSettings.HEAP_DEFAULT_KB, PythonSettings.effectiveHeapKb(null))
        assertEquals(PythonSettings.HEAP_DEFAULT_KB, PythonSettings.effectiveHeapKb(0))
    }

    @Test
    fun overrideKnobWinsOverContextAndIsClamped() {
        // The override is clamped into 16..4096 even when the env var is wild.
        val prev = System.getProperty("HARNESS_PYTHON_HEAP_KB")
        try {
            System.setProperty("HARNESS_PYTHON_HEAP_KB", "999999")
            assertEquals(4096, PythonSettings.effectiveHeapKb(2048))
            System.setProperty("HARNESS_PYTHON_HEAP_KB", "128")
            assertEquals(128, PythonSettings.effectiveHeapKb(32768))
            assertEquals("override", PythonSettings.heapSource(32768))
        } finally {
            if (prev == null) System.clearProperty("HARNESS_PYTHON_HEAP_KB") else System.setProperty("HARNESS_PYTHON_HEAP_KB", prev)
        }
    }

    @Test
    fun heapSourceReportsContextWhenNoOverride() {
        assertEquals("context(8192)", PythonSettings.heapSource(8192))
        assertEquals("context(probe-failed)", PythonSettings.heapSource(null))
    }

    @Test
    fun timeoutIsClampedToSaneBounds() {
        assertEquals(PythonSettings.TIMEOUT_DEFAULT_MS, PythonSettings.effectiveTimeoutMs())
        val prev = System.getProperty("HARNESS_PYTHON_TIMEOUT_MS")
        try {
            System.setProperty("HARNESS_PYTHON_TIMEOUT_MS", "10")
            assertEquals(PythonSettings.TIMEOUT_MIN_MS, PythonSettings.effectiveTimeoutMs())
            System.setProperty("HARNESS_PYTHON_TIMEOUT_MS", "999999999")
            assertEquals(PythonSettings.TIMEOUT_MAX_MS, PythonSettings.effectiveTimeoutMs())
        } finally {
            if (prev == null) System.clearProperty("HARNESS_PYTHON_TIMEOUT_MS") else System.setProperty("HARNESS_PYTHON_TIMEOUT_MS", prev)
        }
    }

    @Test
    fun enabledByDefaultAndKillSwitchHonoured() {
        val prev = System.getProperty("HARNESS_PYTHON_ENABLED")
        try {
            System.setProperty("HARNESS_PYTHON_ENABLED", "0")
            assertFalse(PythonSettings.enabled)
            System.setProperty("HARNESS_PYTHON_ENABLED", "1")
            assertTrue(PythonSettings.enabled)
        } finally {
            if (prev == null) System.clearProperty("HARNESS_PYTHON_ENABLED") else System.setProperty("HARNESS_PYTHON_ENABLED", prev)
        }
    }
}
