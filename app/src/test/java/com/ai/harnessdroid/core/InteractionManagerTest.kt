package com.ai.harnessdroid.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

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

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val prefsMap = mutableMapOf<String, Boolean>()

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        prefsMap.clear()

        `when`(mockContext.getSharedPreferences("harness_permissions", Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        
        `when`(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            val key = inv.arguments[0] as String
            val defaultVal = inv.arguments[1] as Boolean
            prefsMap[key] ?: defaultVal
        }
        
        `when`(mockPrefs.all).thenAnswer { prefsMap }

        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            val key = inv.arguments[0] as String
            val value = inv.arguments[1] as Boolean
            prefsMap[key] = value
            mockEditor
        }
        
        `when`(mockEditor.clear()).thenAnswer {
            prefsMap.clear()
            mockEditor
        }
    }

    @Test
    fun approvedToolIsWhitelistedUntilReset() = runBlocking {
        val handler = CountingApprovalHandler()
        val manager = InteractionManager(mockContext, handler)

        // First call asks the human, second call is whitelisted.
        assertTrue(manager.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals(1, handler.permissionCalls)
        
        // Second call should hit the mocked SharedPreferences
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
        val manager = InteractionManager(mockContext, handler)

        assertFalse(manager.requireIntentPermission("send_email", "com.example.mail", "{}"))
        assertEquals(0, manager.resetApprovedTools())
        // Denied pairs must be re-asked every time.
        assertFalse(manager.requireIntentPermission("send_email", "com.example.mail", "{}"))
        assertEquals(2, handler.permissionCalls)
    }
    
    @Test
    fun permissionsSurviveManagerRecreation() = runBlocking {
        val handler1 = CountingApprovalHandler()
        val manager1 = InteractionManager(mockContext, handler1)

        // Grant permission in first manager instance
        assertTrue(manager1.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals(1, handler1.permissionCalls)
        
        // Re-create manager (simulating app restart)
        val handler2 = CountingApprovalHandler()
        val manager2 = InteractionManager(mockContext, handler2)
        
        // It should use SharedPreferences and not ask again
        assertTrue(manager2.requireIntentPermission("launch_app", "com.google.android.gm", "{}"))
        assertEquals(0, handler2.permissionCalls)
    }
}
