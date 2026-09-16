package com.ai.harnessdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.memory.VectorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Purge & Stop primitives, exercised on-device with a real Context (the same
 * file layout the app uses under filesDir). The forensic log is never touched:
 * ForensicLogger has no purge API on purpose, which these tests rely on.
 */
@RunWith(AndroidJUnit4::class)
class PurgeStopInstrumentedTest {

    private fun uniqueDir(parent: File, name: String): File {
        val dir = File(parent, "${name}_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun subtaskSessionsAreDeletedOnDeviceButNotTheActiveSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionsDir = uniqueDir(context.filesDir, "test_sessions")
        val main = File(sessionsDir, "session_1.jsonl.gz").apply { writeText("keep") }
        val sub1 = File(sessionsDir, "subtask_111.jsonl.gz").apply { writeText("drop") }
        val sub2 = File(sessionsDir, "subtask_222.jsonl.gz").apply { writeText("drop") }

        val deleted = com.ai.harnessdroid.core.SessionPersistence.deleteSubtaskSessions(sessionsDir)

        assertEquals(2, deleted)
        assertTrue("active session must survive", main.exists())
        assertFalse(sub1.exists())
        assertFalse(sub2.exists())
        sessionsDir.deleteRecursively()
    }

    @Test
    fun subtaskPurgeWorksWithRealSessionPersistenceFiles() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // SessionPersistence always writes under filesDir/sessions (memoryFile):
        // use a unique session id instead of a unique directory.
        val id = "subtask_${System.nanoTime()}"
        val sub = com.ai.harnessdroid.core.SessionPersistence(context, id)
        sub.initializeLog()
        sub.flushLog(listOf(com.ai.harnessdroid.core.SessionEvent("user", "delegated work")))
        val sessionFile = File(context.filesDir, "sessions/$id.jsonl.gz")
        assertTrue(sessionFile.exists())

        val deleted = com.ai.harnessdroid.core.SessionPersistence
            .deleteSubtaskSessions(File(context.filesDir, "sessions"))
        assertTrue("at least our own subtask file must be deleted", deleted >= 1)
        assertFalse(sessionFile.exists())
    }

    @Test
    fun clarificationStoreClearWipesFilesDirStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = uniqueDir(context.filesDir, "test_clarifications")
        val store = com.ai.harnessdroid.core.ClarificationStore(dir)
        store.save(
            com.ai.harnessdroid.core.ClarificationRecord(
                id = "instr-1",
                prompt = "Which app?",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 120_000L
            )
        )
        assertEquals(1, store.loadAll().size)

        store.clear()

        assertEquals(0, store.loadAll().size)
        assertEquals("[]", File(dir, "clarification_store.json").readText().trim())
        dir.deleteRecursively()
    }

    @Test
    fun vectorStorePurgeAllDropsEveryKindOnDevice() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = uniqueDir(context.filesDir, "test_vectors")
        val store = VectorStore(dir, "qwen0.5b")
        val v = FloatArray(8) { 0.5f }
        assertTrue(store.ingest("history chunk", v, kind = "history"))
        assertTrue(store.ingest("durable fact", v, kind = "memory"))
        assertEquals(2, store.size)

        assertEquals(2, store.purgeAll())
        assertEquals(0, store.size)
        // Dedupe index is cleared: the same text re-ingests successfully.
        assertTrue(store.ingest("history chunk", v, kind = "history"))
        assertEquals(1, store.size)
        dir.deleteRecursively()
    }

    @Test
    fun interactionManagerResetRevokesApprovals() = runBlocking<Unit> {
        var calls = 0
        val handler = object : com.ai.harnessdroid.core.HumanInteractionHandler {
            override suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean {
                calls++
                return true
            }
            override suspend fun askUserForInput(prompt: String, defaultAnswer: String?, timeoutSeconds: Long): String = ""
        }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        // Approvals are persisted in harness_permissions since 4c7f705: clear them so this
        // test starts from a known state and stays idempotent across runs on one device.
        context.getSharedPreferences("harness_permissions", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val manager = com.ai.harnessdroid.core.InteractionManager(context, handler)

        assertTrue(manager.requireIntentPermission("launch_app", "com.example.app", "{}"))
        assertEquals(1, calls)
        assertTrue(manager.requireIntentPermission("launch_app", "com.example.app", "{}"))
        assertEquals("whitelisted: no new prompt", 1, calls)

        assertEquals(1, manager.resetApprovedTools())
        assertTrue(manager.requireIntentPermission("launch_app", "com.example.app", "{}"))
        assertEquals("approval revoked: human asked again", 2, calls)
    }
}
