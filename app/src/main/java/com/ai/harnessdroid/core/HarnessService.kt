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

data class ClarificationRequest(
    val id: String,
    val prompt: String,
    val defaultAnswer: String? = null,
    val timeoutMs: Long = 120_000L
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
    val clarificationRequests = MutableSharedFlow<ClarificationRequest>(extraBufferCapacity = 1)

    private val permissionResponses = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val clarificationResponses = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<String>>()

    // Only one agent task may run at a time: concurrent tasks would corrupt the shared
    // session log and tool registry state.
    private val taskRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // Kept as a field so the clarification fallback can consult the LLM when the
    // human does not answer in time.
    private lateinit var llmClient: com.ai.harnessdroid.llm.LLMClient
    private lateinit var clarificationStore: ClarificationStore
    private lateinit var agentLoop: AgentLoop
    private lateinit var forensicLogger: ForensicLogger
    private lateinit var sessionPersistence: SessionPersistence
    private lateinit var toolRegistry: com.ai.harnessdroid.tools.ToolRegistry

    // Shared embedding space, exposed for the memory UI.
    var vectorStore: com.ai.harnessdroid.memory.VectorStore? = null
        private set
    var memoryService: com.ai.harnessdroid.memory.MemoryService? = null
        private set

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
        
        llmClient = com.ai.harnessdroid.llm.LLMClient(this)
        clarificationStore = ClarificationStore(filesDir)
        val interactionManager = InteractionManager(this)
        toolRegistry = com.ai.harnessdroid.tools.ToolRegistry(this, interactionManager)
        
        sessionPersistence = SessionPersistence(this, "session_1")

        // Shared embedding store: the AgentLoop (history chunks) and the
        // memory features (durable facts) read and write the same space.
        val vectorStore = com.ai.harnessdroid.memory.VectorStore(filesDir, "tree4five")
        val memoryService = com.ai.harnessdroid.memory.MemoryService(
            store = vectorStore,
            embedder = { text -> llmClient.embedText(text) },
            generate = { prompt -> llmClient.generateText(prompt) }
        )

        agentLoop = AgentLoop(llmClient, toolRegistry, sessionPersistence, forensicLogger, vectorStore, memoryService)

        // Exposed for the UI memory screen.
        this.vectorStore = vectorStore
        this.memoryService = memoryService
        
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

    suspend fun getAvailableTools(): String {
        return toolRegistry.discoverAndBindTools()
    }

    fun startTask(request: String) {
        if (!taskRunning.compareAndSet(false, true)) {
            forensicLogger.logEvent("TASK_REJECTED", "A task is already running; new request ignored: $request")
            return
        }
        forensicLogger.logEvent("TASK_START", "Received user request: $request")
        scope.launch {
            try {
                // A new request starts from a clean slate: the previous
                // request's transcript and history vectors must not leak
                // into this task's prompts. Durable user facts (kind=memory)
                // survive by design.
                val previous = sessionPersistence.loadLog()
                sessionPersistence.clearLog()
                val droppedVectors = vectorStore?.purgeKind("history") ?: 0
                forensicLogger.logEvent("SESSION_PURGED", "dropped ${previous.size} session events, $droppedVectors history vectors")

                sessionPersistence.flushLog(listOf(SessionEvent("user", request)))

                val result = agentLoop.runTask(request)
                forensicLogger.logEvent("TASK_END", "Task completed with result: $result")
            } catch (e: Exception) {
                // An uncaught failure here (e.g. SecurityException when no LLM provider
                // is installed) would crash the whole process instead of failing the task.
                forensicLogger.logEvent("TASK_ERROR", "Task failed: ${e.message}")
            } finally {
                taskRunning.set(false)
            }
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

    override suspend fun askUserForInput(prompt: String, defaultAnswer: String?, timeoutSeconds: Long): String {
        val effectiveDefault = defaultAnswer?.trim()?.ifBlank { null }
        val requestId = java.util.UUID.randomUUID().toString()
        val request = ClarificationRequest(
            id = requestId,
            prompt = prompt.trim(),
            defaultAnswer = effectiveDefault,
            timeoutMs = timeoutSeconds.coerceAtLeast(1L) * 1000L
        )
        forensicLogger.logEvent("INPUT_ASK", "Requesting user input (${request.timeoutMs / 1000}s): ${request.prompt}")
        clarificationStore.save(ClarificationRecord(
            id = requestId,
            prompt = request.prompt,
            defaultAnswer = request.defaultAnswer,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + request.timeoutMs,
            source = "pending"
        ))

        // Register the deferred BEFORE emitting so a fast UI response can never
        // fall into the gap between emit and registration.
        val deferred = clarificationResponses.getOrPut(requestId) { kotlinx.coroutines.CompletableDeferred() }
        clarificationRequests.emit(request)

        val response = kotlinx.coroutines.withTimeoutOrNull(request.timeoutMs) { deferred.await() }
        val answer = response?.trim()?.ifBlank { null }
        if (answer != null) {
            forensicLogger.logEvent("INPUT_RESPONSE", "User replied: $answer")
            clarificationStore.save(ClarificationRecord(
                id = requestId,
                prompt = request.prompt,
                defaultAnswer = request.defaultAnswer,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + request.timeoutMs,
                answeredAt = System.currentTimeMillis(),
                humanAnswer = answer,
                finalAnswer = answer,
                source = "human"
            ))
            return answer
        }

        // No human answer in time: fall back to the caller-provided default, or
        // let the LLM give its best-guess answer so the task keeps making
        // progress instead of blocking forever.
        val finalChoice = effectiveDefault ?: try {
            val llmPrompt = """
                <SYSTEM>
                You are a helpful assistant. The user did not answer a clarification question within the allowed time.
                Provide the most likely answer to the clarification, but keep it brief and decisive.
                </SYSTEM>

                <QUESTION>
                ${request.prompt}
                </QUESTION>
            """.trimIndent()
            llmClient.generateText(llmPrompt).trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }

        val chosenAnswer = finalChoice ?: "No clarification was provided by the human."
        forensicLogger.logEvent("INPUT_TIMEOUT", "No human answer; using ${if (effectiveDefault != null) "default" else "LLM fallback"}: $chosenAnswer")
        clarificationStore.save(ClarificationRecord(
            id = requestId,
            prompt = request.prompt,
            defaultAnswer = request.defaultAnswer,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + request.timeoutMs,
            answeredAt = System.currentTimeMillis(),
            finalAnswer = chosenAnswer,
            source = if (effectiveDefault != null) "default" else "llm"
        ))
        return chosenAnswer
    }

    fun providePermissionResponse(requestId: String, approved: Boolean) {
        permissionResponses[requestId]?.complete(approved)
        permissionResponses.remove(requestId)
    }

    fun provideClarificationResponse(requestId: String, answer: String) {
        val trimmed = answer.trim()
        clarificationResponses[requestId]?.complete(trimmed)
        clarificationResponses.remove(requestId)
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
        // Release the binder connections to third-party tool providers.
        toolRegistry.unbindAll()
        job.cancel()
    }
}
