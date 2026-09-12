package com.ai.harnessdroid.memory

import android.util.Log
import com.ai.harnessdroid.core.SessionEvent
import org.json.JSONArray

/**
 * Long-term user memory, kept in embedding space:
 *
 *  - [remember] stores a durable fact as a "memory" vector;
 *  - [recallVectors] returns the top-K fact vectors for injection into the
 *    latent prefix (soft prompt);
 *  - [buildMemoryPrefix] renders the same facts as a short text block — the
 *    dual injection of F6: a 0.5B model largely ignores soft prompts, so the
 *    facts are ALSO injected as text unless HARNESS_MEMORY_TEXT_INJECT=0;
 *  - [extractMemories] spends ONE greedy text call at the end of a task to
 *    pull up to 5 durable facts out of the transcript.
 */
class MemoryService(
    private val store: VectorStore,
    private val embedder: suspend (String) -> FloatArray?,
    private val generate: suspend (String) -> String
) {
    private val TAG = "MemoryService"

    private val textInject: Boolean =
        ((System.getProperty("HARNESS_MEMORY_TEXT_INJECT")
            ?: System.getenv("HARNESS_MEMORY_TEXT_INJECT") ?: "1") == "1")

    /** Stores a durable fact; returns false when embeddings are unavailable or duplicate. */
    suspend fun remember(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val vector = embedder(trimmed) ?: return false
        val stored = store.ingest(trimmed, vector, kind = "memory", role = "user")
        if (stored) Log.i(TAG, "MEMORY_REMEMBER: ${trimmed.take(80)}")
        return stored
    }

    /** Top-K fact texts matching the query. */
    suspend fun recall(query: String, k: Int = 3): List<String> {
        if (query.isBlank()) return emptyList()
        val queryVector = embedder(query) ?: return emptyList()
        return store.search(queryVector, topK = k, kinds = setOf("memory"))
            .map { it.entry.text }
    }

    /** Fact vectors for the latent prefix (dequantized, flattened), or null. */
    suspend fun recallVectors(query: String, k: Int = MEMORY_TOP_K, dim: Int): FloatArray? {
        if (query.isBlank() || dim <= 0) return null
        val queryVector = embedder(query) ?: return null
        val hits = store.search(queryVector, topK = k, kinds = setOf("memory"))
        if (hits.isEmpty()) return null
        val flat = FloatArray(hits.size * dim)
        hits.forEachIndexed { i, scored ->
            val v = scored.entry.dequantized()
            if (v.size != dim) return null
            System.arraycopy(v, 0, flat, i * dim, dim)
        }
        return flat
    }

    /** Text block with the top-K facts, or "" when nothing to inject. */
    suspend fun buildMemoryPrefix(query: String, k: Int = 2): String {
        if (!textInject) return ""
        val facts = recall(query, k)
        if (facts.isEmpty()) return ""
        return "KNOWN USER FACTS (remember these when answering):\n" +
            facts.joinToString("\n") { "- $it" } + "\n\n"
    }

    /**
     * One greedy LLM call over the transcript tail, extracting up to 5
     * durable facts as a JSON string array. Returns the number of NEW facts
     * stored (duplicates are skipped by the store).
     */
    suspend fun extractMemories(sessionLog: List<SessionEvent>): Int {
        if (sessionLog.isEmpty()) return 0
        val tail = sessionLog.takeLast(TRANSCRIPT_EVENTS)
        val transcript = tail.joinToString("\n") { event ->
            "${event.role}: ${event.content.take(400)}"
        }.take(MAX_TRANSCRIPT_CHARS)

        val prompt = """
<SYSTEM>
You extract durable user facts worth remembering across sessions (preferences, identity, environment, recurring goals).
From the transcript below, output ONLY a JSON array of up to 5 short fact strings in English (max 15 words each).
If there is nothing worth remembering, output [].
Example: ["The user prefers concise answers","The device is a Samsung tablet"]
</SYSTEM>

<TRANSCRIPT>
$transcript
</TRANSCRIPT>
""".trimIndent()

        val response = try {
            generate(prompt).trim()
        } catch (e: Exception) {
            Log.w(TAG, "MEMORY_EXTRACT failed: ${e.message}")
            return 0
        }

        val facts = parseFactArray(response)
        var stored = 0
        for (fact in facts) {
            if (remember(fact)) stored++
        }
        if (facts.isNotEmpty()) Log.i(TAG, "MEMORY_EXTRACT_RESULT: ${facts.size} extracted, $stored new")
        return stored
    }

    private fun parseFactArray(response: String): List<String> {
        val start = response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start == -1 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            (0 until arr.length())
                .map { arr.optString(it, "").trim() }
                .filter { it.isNotEmpty() }
                .take(5)
                .map { it.take(200) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /** Fact vectors injected into the latent prefix. */
        const val MEMORY_TOP_K = 2

        /** Transcript tail fed to the extraction call. */
        const val TRANSCRIPT_EVENTS = 12
        const val MAX_TRANSCRIPT_CHARS = 2400
    }
}
