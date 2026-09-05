package com.ai.harnessdroid.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PermissionRequest(
    val id: String,
    val toolName: String,
    val packageName: String,
    val reason: String
)

class HarnessService : Service(), HumanInteractionHandler {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private val _uiState = MutableStateFlow<List<SessionEvent>>(emptyList())
    val uiState: StateFlow<List<SessionEvent>> = _uiState.asStateFlow()

    // Add forensic state so UI can observe it
    private val _forensicState = MutableStateFlow<List<String>>(emptyList())
    val forensicState: StateFlow<List<String>> = _forensicState.asStateFlow()

    val permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    
    private val permissionResponses = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    private lateinit var agentLoop: AgentLoop
    private lateinit var forensicLogger: ForensicLogger
    private lateinit var sessionPersistence: SessionPersistence

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        
        forensicLogger = object : ForensicLogger(this) {
            override fun logEvent(tag: String, message: String) {
                super.logEvent(tag, message)
                // Add to our observable flow for the debug UI
                val logLine = "[$tag] $message"
                _forensicState.value = _forensicState.value + logLine
            }
        }
        
        forensicLogger.logEvent("SYSTEM", "HarnessService started.")
        
        val llmClient = com.ai.harnessdroid.llm.LLMClient(this)
        val interactionManager = InteractionManager(this)
        val toolRegistry = com.ai.harnessdroid.tools.ToolRegistry(this, interactionManager)
        
        sessionPersistence = SessionPersistence(this, "session_1")
        
        agentLoop = AgentLoop(llmClient, toolRegistry, sessionPersistence, forensicLogger)
        
        scope.launch {
            sessionPersistence.initializeLog()
            sessionPersistence.logFlow.collect {
                _uiState.value = it
            }
        }
    }

    fun clearLog() {
        scope.launch {
            sessionPersistence.clearLog()
        }
    }

    fun startTask(request: String) {
        forensicLogger.logEvent("TASK_START", "Received user request: $request")
        scope.launch {
            val currentLog = sessionPersistence.loadLog()
            currentLog.add(SessionEvent("user", request))
            sessionPersistence.flushLog(currentLog)

            val result = agentLoop.runTask(request)
            forensicLogger.logEvent("TASK_END", "Task completed with result: $result")
        }
    }

    override suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean {
        forensicLogger.logEvent("GUARD_ASK", "Requesting user permission for tool: $toolName")
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        permissionResponses[requestId] = deferred
        
        permissionRequests.emit(PermissionRequest(requestId, toolName, intentPackage, reason))
        
        val approved = deferred.await()
        forensicLogger.logEvent("GUARD_RESPONSE", "Permission for $toolName was ${if (approved) "APPROVED" else "DENIED"}")
        return approved
    }

    override suspend fun askUserForInput(prompt: String): String {
        return "User input not implemented in this mock"
    }

    fun providePermissionResponse(requestId: String, approved: Boolean) {
        permissionResponses[requestId]?.complete(approved)
        permissionResponses.remove(requestId)
    }

    private fun createNotification(): Notification {
        val channelId = "harness_channel"
        val channel = NotificationChannel(channelId, "Harness Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, channelId)
            .setContentTitle("Harness Agent Running")
            .setContentText("Autonomous agent is active.")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): HarnessService = this@HarnessService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
