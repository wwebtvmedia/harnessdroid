package com.ai.harnessdroid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingApprovalHandler : HumanInteractionHandler {
    var permissionCalls = 0
    var approve = true

    override suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean {
        permissionCalls++
        return approve
    }

    override suspend fun askUserForInput(prompt: String, defaultAnswer: String?, timeoutSeconds: Long): String = ""
}

class InteractionManagerTest {

    @Test
    fun approvedToolIsWhitelistedUntilReset() = runBlocking {
        val handler = CountingApprovalHandler()
        val manager = InteractionManager(handler)

        // First call asks the human, second call is whitelisted.
        assertTrue(manager.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals(1, handler.permissionCalls)
        assertTrue(manager.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals("whitelisted: no new human prompt", 1, handler.permissionCalls)

        // Purge & Stop revokes every approval and reports the count.
        assertEquals(1, manager.resetApprovedTools())
        assertEquals(0, manager.resetApprovedTools()) // already empty

        // The same (tool, package) pair now asks the human again.
        assertTrue(manager.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals(2, handler.permissionCalls)
    }

    @Test
    fun deniedToolIsNotWhitelisted() = runBlocking {
        val handler = CountingApprovalHandler().apply { approve = false }
        val manager = InteractionManager(handler)

        assertEquals(false, manager.requireIntentPermission("send_email", "com.example.mail", "{}"))
        assertEquals(0, manager.resetApprovedTools())
        // Denied pairs must be re-asked every time.
        assertEquals(false, manager.requireIntentPermission("send_email", "com.example.mail", "{}"))
        assertEquals(2, handler.permissionCalls)
    }
}
