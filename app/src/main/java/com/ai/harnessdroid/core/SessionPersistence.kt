package com.ai.harnessdroid.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class SessionEvent(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("role", role)
        obj.put("content", content)
        obj.put("timestamp", timestamp)
        toolName?.let { obj.put("toolName", it) }
        return obj
    }

    companion object {
        fun fromJson(jsonStr: String): SessionEvent {
            val obj = JSONObject(jsonStr)
            return SessionEvent(
                role = obj.getString("role"),
                content = obj.getString("content"),
                timestamp = obj.getLong("timestamp"),
                toolName = if (obj.has("toolName")) obj.getString("toolName") else null
            )
        }
    }
}

open class SessionPersistence(private val context: Context?, private val sessionId: String) {
    private val TAG = "SessionPersistence"
    
    private val memoryFile: File
        get() = if (context != null) File(context.filesDir, "sessions/${sessionId}.jsonl.gz") else File("/tmp", "sessions/${sessionId}.jsonl.gz")

    private val _logFlow = MutableStateFlow<List<SessionEvent>>(emptyList())
    val logFlow: StateFlow<List<SessionEvent>> = _logFlow.asStateFlow()

    init {
        memoryFile.parentFile?.mkdirs()
    }

    open suspend fun initializeLog() {
        val loaded = loadLog()
        _logFlow.value = loaded
    }

    open suspend fun clearLog() = withContext(Dispatchers.IO) {
        _logFlow.value = emptyList()
        if (memoryFile.exists()) memoryFile.delete()
    }

    open suspend fun flushLog(log: List<SessionEvent>): Unit = withContext(Dispatchers.IO) {
        // Update the flow immediately so the UI reacts instantly without polling
        _logFlow.value = ArrayList(log)
        try {
            FileOutputStream(memoryFile).use { fos ->
                GZIPOutputStream(fos).use { gzipOs ->
                    for (event in log) {
                        val line = event.toJson().toString() + "\n"
                        gzipOs.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush compressed memory", e)
        }
    }

    open suspend fun loadLog(): MutableList<SessionEvent> = withContext(Dispatchers.IO) {
        val loadedLog = mutableListOf<SessionEvent>()
        if (!memoryFile.exists()) return@withContext loadedLog
        
        try {
            FileInputStream(memoryFile).use { fis ->
                GZIPInputStream(fis).use { gzipIs ->
                    BufferedReader(InputStreamReader(gzipIs, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.isNotBlank()) {
                                loadedLog.add(SessionEvent.fromJson(line!!))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load compressed memory", e)
        }
        return@withContext loadedLog
    }
}
