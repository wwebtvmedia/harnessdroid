package com.ai.harnessdroid.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClarificationRecord(
    val id: String,
    val prompt: String,
    val defaultAnswer: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + 120_000L,
    val answeredAt: Long? = null,
    val humanAnswer: String? = null,
    val finalAnswer: String? = null,
    val source: String = "pending"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("prompt", prompt)
        if (!defaultAnswer.isNullOrBlank()) put("defaultAnswer", defaultAnswer)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
        if (answeredAt != null) put("answeredAt", answeredAt)
        if (!humanAnswer.isNullOrBlank()) put("humanAnswer", humanAnswer)
        if (!finalAnswer.isNullOrBlank()) put("finalAnswer", finalAnswer)
        put("source", source)
    }

    companion object {
        fun fromJson(obj: JSONObject): ClarificationRecord = ClarificationRecord(
            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
            prompt = obj.optString("prompt", ""),
            defaultAnswer = obj.optString("defaultAnswer", "").ifBlank { null },
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            expiresAt = obj.optLong("expiresAt", System.currentTimeMillis() + 120_000L),
            answeredAt = if (obj.has("answeredAt")) obj.optLong("answeredAt") else null,
            humanAnswer = obj.optString("humanAnswer", "").ifBlank { null },
            finalAnswer = obj.optString("finalAnswer", "").ifBlank { null },
            source = obj.optString("source", "pending")
        )
    }
}

class ClarificationStore(storageDirectory: File) {
    private val storageFile = File(storageDirectory, "clarification_store.json")

    init {
        storageDirectory.mkdirs()
        if (!storageFile.exists()) {
            storageFile.writeText(JSONArray().toString(2))
        }
    }

    fun loadAll(): List<ClarificationRecord> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(storageFile.readText(Charsets.UTF_8))
            val records = mutableListOf<ClarificationRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                records.add(ClarificationRecord.fromJson(obj))
            }
            records
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(record: ClarificationRecord) {
        val records = loadAll().filterNot { it.id == record.id }.toMutableList()
        records.add(record)
        storageFile.writeText(JSONArray(records.map { it.toJson() }).toString(2), Charsets.UTF_8)
    }

    fun findLatestByPrompt(prompt: String): ClarificationRecord? {
        return loadAll().filter { it.prompt == prompt }.maxByOrNull { it.createdAt }
    }

    fun saveAnswer(prompt: String, answer: String, source: String, defaultAnswer: String? = null): ClarificationRecord {
        val existing = findLatestByPrompt(prompt)
        val now = System.currentTimeMillis()
        val record = (existing ?: ClarificationRecord(
            id = java.util.UUID.randomUUID().toString(),
            prompt = prompt,
            defaultAnswer = defaultAnswer,
            createdAt = now
        )).copy(
            defaultAnswer = defaultAnswer ?: existing?.defaultAnswer,
            humanAnswer = if (source == "human") answer else existing?.humanAnswer,
            finalAnswer = answer,
            source = source,
            answeredAt = now,
            expiresAt = now + 120_000L
        )
        save(record)
        return record
    }
}
