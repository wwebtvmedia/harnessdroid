package com.ai.harnessdroid.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

class VectorStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vector(x: Float, dim: Int = 8): FloatArray = FloatArray(dim) { x }
    private fun mixed(hi: Int, lo: Int, dim: Int = 8): FloatArray =
        FloatArray(dim) { if (it % 2 == 0) hi / 127f else lo / 127f }

    @Test
    fun ingestThenSearchReturnsBestMatch() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "qwen0.5b")
        store.ingest("alpha chunk", mixed(100, 10), kind = "history")
        store.ingest("beta chunk", mixed(-100, 5), kind = "history")
        store.ingest("gamma chunk", mixed(90, -80), kind = "history")
        assertEquals(3, store.size)

        val hits = store.search(mixed(98, 9), topK = 2)
        assertEquals(2, hits.size)
        assertEquals("alpha chunk", hits[0].entry.text)
        assertTrue("best score must be near 1", hits[0].score > 0.99f)
    }

    @Test
    fun identicalTextIsDedupedBySha() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "qwen0.5b")
        assertTrue(store.ingest("same text", vector(1f), kind = "history"))
        assertFalse(store.ingest("same text", vector(1f), kind = "history"))
        assertEquals(1, store.size)
    }

    @Test
    fun nearDuplicateVectorsAreSkipped() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "qwen0.5b")
        assertTrue(store.ingest("first", vector(1f), kind = "history"))
        // cosine(1,1,...; 0.99,0.99,...) == 1 > 0.97 -> skipped.
        assertFalse(store.ingest("second", FloatArray(8) { 0.99f }, kind = "history"))
        // Orthogonal-ish vector passes.
        assertTrue(store.ingest("third", mixed(-64, 64), kind = "history"))
        assertEquals(2, store.size)
    }

    @Test
    fun searchFiltersByKindAndDim() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "qwen0.5b")
        store.ingest("a fact", mixed(64, 1), kind = "memory")
        store.ingest("an event", mixed(64, 2), kind = "history")
        // Foreign dim is ignored, not crashed on.
        store.ingest("wrong space", vector(1f, dim = 4), kind = "history")

        val onlyMemory = store.search(mixed(64, 1), topK = 5, kinds = setOf("memory"))
        assertEquals(1, onlyMemory.size)
        assertEquals("a fact", onlyMemory[0].entry.text)

        val all = store.search(mixed(64, 1), topK = 5)
        assertEquals(2, all.size) // the dim=4 entry is skipped silently
    }

    @Test
    fun evictionCapsEntryCount() = runBlocking {
        val store = VectorStore(tmp.newFolder(), "qwen0.5b", capacity = 5)
        for (i in 0 until 10) {
            // Distinct texts AND near-orthogonal vectors: dedupe must not kick in.
            val v = FloatArray(8) { d -> if (d == i % 8) 1f else 0f }
            store.ingest("chunk $i", v, kind = "history")
        }
        assertEquals(5, store.size)
        // The OLDEST entries were evicted.
        val hits = store.search(FloatArray(8) { d -> if (d == 0) 1f else 0f }, topK = 5)
        assertTrue(hits.none { it.entry.text == "chunk 0" })
        assertTrue(hits.any { it.entry.text == "chunk 8" })
    }

    @Test
    fun flushPersistsAndReloadRestoresIndex() = runBlocking {
        val dir = tmp.newFolder()
        val store = VectorStore(dir, "qwen0.5b")
        store.ingest("persist me", mixed(70, 20), kind = "history")
        store.ingest("me too", mixed(-70, 20), kind = "memory")
        store.flush(force = true)

        val file = File(dir, "memory/vectors.jsonl.gz")
        assertTrue(file.exists())
        // Content is gzipped JSONL.
        val content = GZIPInputStream(file.inputStream()).bufferedReader().readLines()
        assertEquals(2, content.size)
        assertTrue(content[0].contains("\"kind\":\"history\""))

        val reloaded = VectorStore(dir, "qwen0.5b")
        assertEquals(2, reloaded.size)
        val hits = reloaded.search(mixed(70, 20), topK = 1)
        assertEquals("persist me", hits[0].entry.text)
    }

    @Test
    fun throttledFlushHappensEverySixteenInserts() = runBlocking {
        val dir = tmp.newFolder()
        val store = VectorStore(dir, "qwen0.5b", capacity = 100)
        for (i in 0 until 16) {
            val v = FloatArray(8) { d -> if (d == i % 8) 1f else 0f }
            store.ingest("auto flush $i", v, kind = "history")
        }
        assertTrue(File(dir, "memory/vectors.jsonl.gz").exists())
    }
}
