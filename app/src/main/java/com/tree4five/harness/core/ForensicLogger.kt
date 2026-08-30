package com.tree4five.harness.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Forensics and Safety Logger.
 * Records a detailed, tamper-evident (in a real scenario, this could be signed) log 
 * of the agent's internal thinking, plans, and tool execution.
 * Critical for debugging, auditing, and ensuring safety in embedded environments.
 */
class ForensicLogger(context: Context) {
    private val logFile = File(context.filesDir, "forensics_safety.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        if (!logFile.exists()) logFile.createNewFile()
    }

    @Synchronized
    fun logEvent(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] [$tag] $message\n"
        
        // Log to Logcat for real-time debugging
        Log.d("Forensics", logEntry.trim())
        
        // Append to local persistent file for safety audits
        try {
            FileOutputStream(logFile, true).use { fos ->
                fos.write(logEntry.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e("Forensics", "Failed to write to forensic log", e)
        }
    }

    fun readForensics(): String {
        return if (logFile.exists()) logFile.readText() else "No forensic data available."
    }
}
