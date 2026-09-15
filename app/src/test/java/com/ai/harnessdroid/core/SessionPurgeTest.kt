package com.ai.harnessdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionPurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sessionsDir(): File {
        val dir = File(tmp.newFolder(), "sessions")
        dir.mkdirs()
        return dir
    }

    private fun touch(dir: File, name: String): File {
        val f = File(dir, name)
        f.writeText("x")
        return f
    }

    @Test
    fun deletesOnlySubtaskFilesAndKeepsActiveSession() {
        val dir = sessionsDir()
        val main = touch(dir, "session_1.jsonl.gz")
        val sub1 = touch(dir, "subtask_1700000000000.jsonl.gz")
        val sub2 = touch(dir, "subtask_1700000000001.jsonl.gz")
        val other = touch(dir, "notes.txt")

        val deleted = SessionPersistence.deleteSubtaskSessions(dir)

        assertEquals(2, deleted)
        assertTrue("active session must survive", main.exists())
        assertTrue("unrelated files must survive", other.exists())
        assertFalse(sub1.exists())
        assertFalse(sub2.exists())
    }

    @Test
    fun secondCallIsANoOp() {
        val dir = sessionsDir()
        touch(dir, "subtask_1.jsonl.gz")

        assertEquals(1, SessionPersistence.deleteSubtaskSessions(dir))
        assertEquals(0, SessionPersistence.deleteSubtaskSessions(dir))
    }

    @Test
    fun handlesMissingAndNullDirectories() {
        assertEquals(0, SessionPersistence.deleteSubtaskSessions(null))
        assertEquals(0, SessionPersistence.deleteSubtaskSessions(File(tmp.root, "does-not-exist")))
        // An existing but empty directory is fine too.
        assertEquals(0, SessionPersistence.deleteSubtaskSessions(sessionsDir()))
    }
}
