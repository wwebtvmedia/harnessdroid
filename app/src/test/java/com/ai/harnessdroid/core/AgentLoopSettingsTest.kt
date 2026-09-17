package com.ai.harnessdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopSettingsTest {

    @Test
    fun keepsInRunLoopCounts() {
        assertEquals(1, AgentLoopSettings.clamp(1))
        assertEquals(10, AgentLoopSettings.clamp(10))
        assertEquals(AgentLoopSettings.MAX, AgentLoopSettings.clamp(AgentLoopSettings.MAX))
    }

    @Test
    fun clampsOutOfRangeLoopCounts() {
        assertEquals(AgentLoopSettings.MIN, AgentLoopSettings.clamp(0))
        assertEquals(AgentLoopSettings.MIN, AgentLoopSettings.clamp(-5))
        // A stray "999" typed in the dialog must never become the loop budget.
        assertEquals(AgentLoopSettings.MAX, AgentLoopSettings.clamp(999))
    }

    @Test
    fun defaultIsTheHistoricalLoopBudget() {
        assertEquals(10, AgentLoopSettings.DEFAULT)
        assertTrue(
            "the default must be inside the accepted range",
            AgentLoopSettings.DEFAULT in AgentLoopSettings.MIN..AgentLoopSettings.MAX
        )
    }
}
