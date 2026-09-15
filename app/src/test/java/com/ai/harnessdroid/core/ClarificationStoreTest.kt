package com.ai.harnessdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClarificationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: String, prompt: String) = ClarificationRecord(
        id = id,
        prompt = prompt,
        createdAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 120_000L
    )

    @Test
    fun clearWipesEveryRecordAndPersistsEmptyArray() {
        val dir = tmp.newFolder()
        val store = ClarificationStore(dir)
        store.save(record("1", "Which app?"))
        store.save(record("2", "Which file?"))
        assertEquals(2, store.loadAll().size)

        store.clear()

        assertEquals(0, store.loadAll().size)
        // The file survives as an empty JSON array, like a fresh store.
        val file = File(dir, "clarification_store.json")
        assertTrue(file.exists())
        assertEquals("[]", file.readText().trim())
    }

    @Test
    fun clearThenSaveWorksNormally() {
        val store = ClarificationStore(tmp.newFolder())
        store.save(record("1", "Which app?"))
        store.clear()

        store.save(record("2", "Which file?"))

        val all = store.loadAll()
        assertEquals(1, all.size)
        assertEquals("Which file?", all[0].prompt)
    }
}
