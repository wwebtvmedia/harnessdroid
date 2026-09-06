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
        assertTrue(actions.contains(Intent.ACTION_MAIN))
    }
}
