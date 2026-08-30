package com.tree4five.harness.core

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

/**
 * Background Foreground Service keeping the Agent alive.
 * Acts as the bridge between the UI (Frontend) and the AgentLoop.
 */
class HarnessService : Service(), HumanInteractionHandler {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    // UI State exposed to the Frontend
    private val _uiState = MutableStateFlow<List<SessionEvent>>(emptyList())
    val uiState: StateFlow<List<SessionEvent>> = _uiState.asStateFlow()

    // Channel to push permission requests to the UI popup
    val permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    
    // Channel to receive human decisions back from the UI
    private val permissionResponses = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    private lateinit var agentLoop: AgentLoop
    private lateinit var forensicLogger: ForensicLogger

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        
        forensicLogger = ForensicLogger(this)
        forensicLogger.logEvent("SYSTEM", "HarnessService started.")
        
        // Initialize dependencies (In a real app, use Dagger/Hilt)
        val llmClient = com.tree4five.harness.llm.LLMClient(this)
        val interactionManager = InteractionManager(this)
        val toolRegistry = com.tree4five.harness.tools.ToolRegistry(this, interactionManager)
        val sessionPersistence = SessionPersistence(this, "session_1")
        
        agentLoop = AgentLoop(llmClient, toolRegistry, sessionPersistence, forensicLogger)
        
        // Load initial state for the UI
        scope.launch {
            _uiState.value = sessionPersistence.loadLog()
        }
    }

    /**
     * Called by the UI to execute a request
     */
    fun startTask(request: String) {
        forensicLogger.logEvent("TASK_START", "Received user request: $request")
        scope.launch {
            val result = agentLoop.runTask(request)
            forensicLogger.logEvent("TASK_END", "Task completed with result: $result")
            // Update UI state
            _uiState.value = SessionPersistence(this@HarnessService, "session_1").loadLog()
        }
    }

    /**
     * Implements HumanInteractionHandler.
     * Suspends the AgentLoop until the UI provides an answer.
     */
    override suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean {
        forensicLogger.logEvent("GUARD_ASK", "Requesting user permission for tool: $toolName")
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        permissionResponses[requestId] = deferred
        
        // Push the request to the UI
        permissionRequests.emit(PermissionRequest(requestId, toolName, intentPackage, reason))
        
        // Suspend until the UI calls providePermissionResponse()
        val approved = deferred.await()
        forensicLogger.logEvent("GUARD_RESPONSE", "Permission for $toolName was ${if (approved) "APPROVED" else "DENIED"}")
        return approved
    }

    override suspend fun askUserForInput(prompt: String): String {
        // Simplified for this example, but would follow the same CompletableDeferred pattern
        return "User input not implemented in this mock"
    }

    /**
     * Called by the UI when the user clicks Allow/Deny on the popup
     */
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

    override fun onBind(intent: Intent?): IBinder? = null // Using bound service flow is better, but simplified here

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
