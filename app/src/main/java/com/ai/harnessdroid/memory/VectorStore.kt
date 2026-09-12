package com.ai.harnessdroid.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One stored vector with its source text (the text is never injected into a prompt). */
data class VectorEntry(
    val id: String,
    val kind: String,           // "history" (task events) or "memory" (durable user facts)
    val modelTag: String,       // embedding space identity (provider version + dim)
    val dim: Int,
    val q8: ByteArray,          // raw int8 payload (scale kept apart)
    val scale: Float,
    val text: String,
    val role: String,
    val toolName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val hits: Int = 0
) {
    fun dequantized(): FloatArray = EmbeddingCodec.dequantize(q8, scale)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("modelTag", modelTag)
        put("dim", dim)
        put("q8", java.util.Base64.getEncoder().encodeToString(q8))
        put("scale", scale.toDouble())
        put("text", text)
        put("role", role)
        if (toolName != null) put("toolName", toolName)
        put("createdAt", createdAt)
        put("hits", hits)
    }

    override fun equals(other: Any?): Boolean = other is VectorEntry && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun fromJson(obj: JSONObject): VectorEntry = VectorEntry(
            id = obj.optString("id", UUID.randomUUID().toString()),
            kind = obj.optString("kind", "history"),
            modelTag = obj.optString("modelTag", "unknown"),
            dim = obj.optInt("dim", 0),
            q8 = java.util.Base64.getDecoder().decode(obj.optString("q8", "")),
            scale = obj.optDouble("scale", 1.0).toFloat(),
            text = obj.optString("text", ""),
            role = obj.optString("role", "system"),
            toolName = obj.optString("toolName", "").ifBlank { null },
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            hits = obj.optInt("hits", 0)
        )
    }
}

data class ScoredEntry(val entry: VectorEntry, val score: Float)

/**
 * On-device vector store, all in embedding space:
 *  - entries persisted as JSONL gzipped in `dir/vectors.jsonl.gz`;
 *  - RAM index (insertion order), cosine retrieval;
 *  - dedupe: identical text sha256 OR cosine > DEDUPE_COSINE vs the newest
 *    same-kind entry is skipped (a re-ingested event adds no new vector);
 *  - eviction beyond [capacity] oldest entries first;
 *  - flush is throttled (every FLUSH_EVERY inserts) and force-flushed at the
 *    end of a task run.
 *
 * All mutating/reading entry points are suspend and serialize on [mutex];
 * disk I/O happens on Dispatchers.IO.
 */
class VectorStore(
    dir: File,
    private val modelTag: String,
    private val capacity: Int = MAX_ENTRIES
) {
    private val storeFile = File(File(dir, "memory"), "vectors.jsonl.gz")
    private val mutex = Mutex()
    private val entries = ArrayList<VectorEntry>()
    private val textShaIndex = HashMap<String, VectorEntry>()
    private var insertsSinceFlush = AtomicInteger(0)

    init {
        File(dir, "memory").mkdirs()
        load()
    }

    val size: Int get() = synchronized(entries) { entries.size }

    /** Snapshot of stored entries, optionally filtered by kind (newest last). */
    fun allEntries(kind: String? = null): List<VectorEntry> = synchronized(entries) {
        entries.filter { kind == null || it.kind == kind }
    }

    /**
     * Stores a chunk. Returns false when it was skipped as a duplicate.
     * `dim` mismatch with the current modelTag is allowed across sessions;
     * retrieval only compares vectors of the same modelTag.
     */
    suspend fun ingest(
        text: String,
        vector: FloatArray,
        kind: String,
        role: String = "system",
        toolName: String? = null
    ): Boolean = withContext(Dispatchers.IO) { ingestLocked(text, vector, kind, role, toolName) }

    private suspend fun ingestLocked(
        text: String,
        vector: FloatArray,
        kind: String,
        role: String,
        toolName: String?
    ): Boolean = mutex.withLock {
        if (vector.isEmpty()) return false

        val sha = sha256(text)
        if (textShaIndex.containsKey(sha)) return false

        // Near-duplicate check against the most recent same-kind entry.
        val recent = synchronized(entries) { entries.lastOrNull { it.kind == kind } }
        if (recent != null && recent.dim == vector.size) {
            if (EmbeddingCodec.cosine(recent.dequantized(), vector) > DEDUPE_COSINE) return false
        }

        val (q8, scale) = EmbeddingCodec.quantize(vector)
        val entry = VectorEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            modelTag = modelTag,
            dim = vector.size,
            q8 = q8,
            scale = scale,
            text = text,
            role = role,
            toolName = toolName
        )
        synchronized(entries) {
            entries.add(entry)
            textShaIndex[sha] = entry
            // Evict oldest beyond capacity.
            var removed = 0
            while (entries.size > capacity) {
                val victim = entries.removeAt(0)
                textShaIndex.remove(sha256(victim.text))
                removed++
            }
            if (removed > 0) Log.d(TAG, "evicted $removed vectors (capacity=$capacity)")
        }
        val pending = insertsSinceFlush.incrementAndGet()
        if (pending >= FLUSH_EVERY) {
            flushLocked()
        }
        true
    }

    /** Top-K most similar entries of the given kinds, newest first on ties. */
    suspend fun search(
        query: FloatArray,
        topK: Int = 4,
        kinds: Set<String> = setOf("history", "memory")
    ): List<ScoredEntry> = withContext(Dispatchers.IO) { mutex.withLock {
        if (query.isEmpty() || topK <= 0) return@withLock emptyList()
        val scored = ArrayList<ScoredEntry>()
        synchronized(entries) {
            for (e in entries) {
                if (e.kind !in kinds) continue
                if (e.dim != query.size) continue   // foreign embedding space
                scored.add(ScoredEntry(e, EmbeddingCodec.cosine(e.dequantized(), query)))
            }
        }
        scored.sortByDescending { it.score }
        scored.take(topK)
    } }

    /** Persists the store; throttled unless [force]. */
    suspend fun flush(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!force && insertsSinceFlush.get() == 0) return@withLock
            flushLocked()
        }
    }

    /**
     * Drops every entry of [kind] (e.g. the previous request's "history"
     * vectors) and persists immediately. Returns how many were removed.
     * Other kinds — durable user facts ("memory") — are left untouched.
     */
    suspend fun purgeKind(kind: String): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val removed = synchronized(entries) {
            val before = entries.size
            entries.removeAll { it.kind == kind }
            before - entries.size
        }
        if (removed > 0) flushLocked()
        removed
    } }

    private fun flushLocked() {
        try {
            val snapshot = synchronized(entries) { ArrayList(entries) }
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                GZIPOutputStream(fos).use { gz ->
                    for (e in snapshot) {
                        gz.write((e.toJson().toString() + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
            if (!tmp.renameTo(storeFile)) {
                storeFile.delete()
                if (!tmp.renameTo(storeFile)) Log.e(TAG, "failed to replace $storeFile")
            }
            insertsSinceFlush.set(0)
            Log.i(TAG, "VECTOR_STORE_FLUSH persisted ${snapshot.size} vectors")
        } catch (e: Exception) {
            Log.e(TAG, "vector store flush failed", e)
        }
    }

    private fun load() {
        if (!storeFile.exists()) return
        try {
            GZIPInputStream(storeFile.inputStream()).use { gz ->
                val lines = gz.bufferedReader(Charsets.UTF_8).readLines()
                synchronized(entries) {
                    for (line in lines) {
                        if (line.isBlank()) continue
                        try {
                            entries.add(VectorEntry.fromJson(JSONObject(line)))
                        } catch (_: Exception) {
                            // Skip a corrupted line, keep the rest.
                        }
                    }
                    for (e in entries) textShaIndex[sha256(e.text)] = e
                }
            }
            Log.i(TAG, "loaded ${entries.size} vectors from $storeFile")
        } catch (e: Exception) {
            Log.e(TAG, "vector store load failed, starting empty", e)
            synchronized(entries) { entries.clear() }
        }
    }

    companion object {
        private const val TAG = "VectorStore"

        /** Hard cap on stored vectors (bounded disk + RAM footprint). */
        const val MAX_ENTRIES = 2000

        /** Near-duplicate threshold; two chunks this similar carry no new info. */
        const val DEDUPE_COSINE = 0.97f

        /** Persist to disk every N ingests (the agent loop force-flushes too). */
        const val FLUSH_EVERY = 16

        fun sha256(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
