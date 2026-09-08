package com.ai.harnessdroid.tools

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun toolDiscoveryUsesStandardAndroidIntents() {
        val actions = ToolRegistry(null, null).discoveryIntentActions()

        assertTrue(actions.contains(Intent.ACTION_VIEW))
        assertTrue(actions.contains(Intent.ACTION_SENDTO))
        assertTrue(actions.contains(Intent.ACTION_SEND))
        assertTrue(actions.contains(Intent.ACTION_SEND_MULTIPLE))
        assertTrue(actions.contains(Intent.ACTION_DIAL))
        assertTrue(actions.contains(Intent.ACTION_ASSIST))
        assertTrue(actions.contains(Intent.ACTION_PROCESS_TEXT))
        assertTrue(actions.contains(Intent.ACTION_MAIN))
        assertTrue(actions.size >= 10)
    }
}
