package com.ai.harnessdroid.memory

import android.util.Log
import com.ai.harnessdroid.core.SessionEvent

/**
 * Bridges the agent loop and the vector store, staying in embedding space:
 *
 *  - [ingestNew] chunkizes ONLY the events not yet embedded (incremental,
 *    tracked by [embeddedUpTo]) and stores their vectors. On elision the
 *    caller rewinds [embeddedUpTo]: the store itself is the history memory,
 *    so dropped text loses nothing.
 *  - [retrieveLatent] returns the top-K chunk vectors flattened as one
 *    float array ready to be injected as a soft prompt through
 *    LLMClient.generateFromEmbeddings.
 *
 * The source text stays in the store for forensic/debug purposes but is
 * NEVER injected back into a prompt: retrieval output is the vectors
 * themselves.
 */
class ContextEmbedder(
    private val store: VectorStore,
    private val embedder: suspend (String) -> FloatArray?
) {
    private val TAG = "ContextEmbedder"

    /** Index of the first session event not yet embedded. */
    var embeddedUpTo: Int = 0
        private set

    /** Total chunks stored this session (for forensic lines). */
    var ingestedChunks: Int = 0
        private set

    /**
     * Embeds and stores every event beyond [embeddedUpTo], chunked at
     * [chunkChars]; at most [maxChunks] new chunks per call to bound the
     * per-turn cost. Skips blank and already-stored text (sha dedupe in the
     * store makes re-ingestion harmless).
     */
    suspend fun ingestNew(events: List<SessionEvent>, maxChunks: Int = 4): Int {
        var produced = 0
        var index = embeddedUpTo
        while (index < events.size && produced < maxChunks) {
            val event = events[index]
            index++
            val content = event.content.trim()
            if (content.isEmpty()) continue
            val label = when (event.role) {
                "tool" -> "<TOOL_RESULT name=\"${event.toolName ?: ""}\">"
                "user" -> "<USER_MSG>"
                "assistant" -> "<ASSISTANT_MSG>"
                else -> "<SYSTEM_MSG>"
            }
            var start = 0
            while (start < content.length && produced < maxChunks) {
                val end = minOf(start + chunkChars, content.length)
                val chunk = "$label\n${content.substring(start, end)}"
                val vector = embedder(chunk)
                if (vector != null && vector.isNotEmpty()) {
                    if (store.ingest(chunk, vector, kind = "history", role = event.role, toolName = event.toolName)) {
                        produced++
                        ingestedChunks++
                    }
                } else {
                    Log.w(TAG, "embedder returned no vector; chunk skipped")
                }
                start = end
            }
        }
        embeddedUpTo = index
        if (produced > 0) Log.d(TAG, "EMBD_CTX_INGEST stored $produced new chunks (store=${store.size})")
        return produced
    }

    /** Rewinds the cursor after history elision: remaining events are [count]. */
    fun rewind(count: Int) {
        embeddedUpTo = embeddedUpTo.coerceAtMost(count)
    }

    /**
     * Top-K chunk vectors for `query`, flattened to [k * dim] floats, or
     * null when nothing usable was found (caller falls back to text).
     */
    suspend fun retrieveLatent(query: String, k: Int = TOP_K, dim: Int): FloatArray? {
        if (query.isBlank() || dim <= 0) return null
        val queryVector = embedder(query) ?: return null
        val hits = store.search(queryVector, topK = k, kinds = setOf("history"))
        if (hits.isEmpty()) return null
        val flat = FloatArray(hits.size * dim)
        hits.forEachIndexed { i, scored ->
            val v = scored.entry.dequantized()
            if (v.size != dim) return null   // foreign space: refuse rather than corrupt
            System.arraycopy(v, 0, flat, i * dim, dim)
        }
        Log.d(TAG, "EMBD_CTX_RETRIEVE k=${hits.size} best=${hits.maxOf { it.score }}")
        return flat
    }

    companion object {
        /** ~900 chars: a few dozen tokens of context per latent chunk. */
        const val chunkChars = 900

        /** Latent prefix budget per FSM call (F4: keep room for the followup text). */
        const val TOP_K = 4
    }
}
