package com.ai.harnessdroid.memory

import com.ai.harnessdroid.core.SessionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Deterministic content-dependent embedder; output range is [-1, 1]. */
    private fun embedder(dim: Int = 64): suspend (String) -> FloatArray? = { text ->
        if (dim <= 0) null
        else FloatArray(dim) { i ->
            // floorMod: keep the value in range even for negative hashes.
            (java.lang.Math.floorMod(text.hashCode() * 31 + i * 17, 13) - 6) / 6f
        }
    }

    @Test
    fun rememberStoresFactAndRecallFindsIt() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val service = MemoryService(store, embedder(), generate = { "[]" })

        assertTrue(service.remember("The user prefers concise answers"))
        assertEquals(1, store.size)

        val recalled = service.recall("concise answers preference")
        assertTrue("fact not recalled", recalled.isNotEmpty())
        assertEquals("The user prefers concise answers", recalled[0])
    }

    @Test
    fun rememberRejectsBlankAndDuplicates() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val service = MemoryService(store, embedder(), generate = { "[]" })

        assertFalse(service.remember("   "))
        assertTrue(service.remember("Runs every morning at 7am"))
        assertFalse(service.remember("Runs every morning at 7am"))
        assertEquals(1, store.size)
    }

    @Test
    fun buildMemoryPrefixRendersFactsAsText() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val service = MemoryService(store, embedder(), generate = { "[]" })
        service.remember("The device is a Samsung tablet")

        val prefix = service.buildMemoryPrefix("what device am I using?")
        assertTrue(prefix.contains("KNOWN USER FACTS"))
        assertTrue(prefix.contains("The device is a Samsung tablet"))
    }

    @Test
    fun recallVectorsReturnsDequantizedLatentPrefix() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val service = MemoryService(store, embedder(dim = 32), generate = { "[]" })
        service.remember("fact one")
        service.remember("fact two")

        val vectors = service.recallVectors("facts", dim = 32)
        assertTrue(vectors != null)
        // 2 stored facts x dim 32, values within the dequantized int8 range.
        assertEquals(2 * 32, vectors!!.size)
        assertTrue(vectors.all { it in -2f..2f })
    }

    @Test
    fun extractMemoriesParsesJsonArrayAndStoresNewFacts() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val generated = mutableListOf<String>()
        val service = MemoryService(store, embedder(), generate = { prompt ->
            generated.add(prompt)
            "[\"The user prefers French\",\"The tablet is a SM-P610\"]"
        })
        val log = listOf(
            SessionEvent("user", "Retiens que je préfère le français."),
            SessionEvent("assistant", "Compris, je répondrai en français.")
        )

        val stored = service.extractMemories(log)

        assertEquals(2, stored)
        assertEquals(2, store.size)
        // The extraction call carried the transcript.
        assertTrue(generated[0].contains("TRANSCRIPT"))
        assertTrue(generated[0].contains("je préfère le français"))
        // The facts are recallable afterwards.
        val recalled = service.recall("langue préférée")
        assertTrue(recalled.contains("The user prefers French"))
    }

    @Test
    fun extractMemoriesSurvivesGarbageOutput() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        val service = MemoryService(store, embedder(), generate = { "I could not find anything useful." })
        assertEquals(0, service.extractMemories(listOf(SessionEvent("user", "hello"))))
        assertEquals(0, store.size)
    }

    @Test
    fun extractMemoriesWithNoTranscriptDoesNothing() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "test")
        var called = 0
        val service = MemoryService(store, embedder(), generate = { called++; "[]" })
        assertEquals(0, service.extractMemories(emptyList()))
        assertEquals(0, called)
    }
}
