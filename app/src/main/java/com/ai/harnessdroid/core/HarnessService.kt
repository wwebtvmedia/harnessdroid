package com.ai.harnessdroid.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // The running task's Job, kept so Purge & Stop can cancel it. AtomicReference:
    // startTask runs on the (binder) main thread, the purge runs on Dispatchers.IO.
    private val taskJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    // Guards against two overlapping purges.
    private val purging = java.util.concurrent.atomic.AtomicBoolean(false)

    // Mirrors `taskRunning` for the UI (the AtomicBoolean stays the CAS gate).
    private val _taskRunning = MutableStateFlow(false)
    val taskRunningState: StateFlow<Boolean> = _taskRunning.asStateFlow()

    // Bumped after every purge so the UI can drop stale permission/clarification dialogs.
    private val _purgeEpoch = MutableStateFlow(0)
    val purgeEpoch: StateFlow<Int> = _purgeEpoch.asStateFlow()

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 8_000L
    }

    // Kept as a field so the clarification fallback can consult the LLM when the
    // human does not answer in time.
    private lateinit var llmClient: com.ai.harnessdroid.llm.LLMClient
    private lateinit var clarificationStore: ClarificationStore
    private lateinit var interactionManager: InteractionManager
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
        val interactionManager = InteractionManager(this, this).also { this.interactionManager = it }
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

        // Sub-agent delegation: a delegate_task call spins a fresh AgentLoop with its
        // own session (own context window) over a registry that cannot delegate again
        // (depth capped at 1). Only the sub-agent's final answer joins this context.
        toolRegistry.delegateHandler = { task, agentType ->
            runSubAgent(task, agentType, vectorStore, memoryService)
        }

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
            // Explicit user action (Clear button): wipe the whole working
            // context — transcript, history vectors and the embedder's
            // ingest cursor. Durable user facts (kind=memory) survive.
            val previous = sessionPersistence.loadLog()
            sessionPersistence.clearLog()
            val droppedVectors = vectorStore?.purgeKind("history") ?: 0
            agentLoop.resetContext()
            forensicLogger.logEvent("SESSION_PURGED", "dropped ${previous.size} session events, $droppedVectors history vectors")
        }
    }

    /**
     * Runs one delegated sub-task on a fresh session and returns the tool-result
     * JSON for the main agent. Fewer turns than the main loop: a delegation that
     * cannot converge in 6 steps should report back instead of grinding.
     */
    private suspend fun runSubAgent(
        task: String,
        agentType: String,
        vectorStore: com.ai.harnessdroid.memory.VectorStore?,
        memoryService: com.ai.harnessdroid.memory.MemoryService?
    ): String {
        forensicLogger.logEvent("DELEGATE_START", "agent_type=$agentType task=$task")
        var subRegistry: com.ai.harnessdroid.tools.ToolRegistry? = null
        return try {
            subRegistry = com.ai.harnessdroid.tools.ToolRegistry(this, interactionManager, allowDelegation = false)
            val subSession = SessionPersistence(this, "subtask_${System.currentTimeMillis()}")
            subSession.initializeLog()
            val subLoop = AgentLoop(llmClient, subRegistry, subSession, forensicLogger, vectorStore, memoryService)
            val answer = subLoop.runTask(task, maxTurns = 6)
            forensicLogger.logEvent("DELEGATE_END", "agent_type=$agentType answer=${answer.take(200)}")
            org.json.JSONObject()
                .put("result", answer)
                .put("subagent", agentType)
                .toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A purged/stopped task must unwind as a cancellation, not report an error.
            throw e
        } catch (e: Exception) {
            forensicLogger.logEvent("DELEGATE_ERROR", "agent_type=$agentType failed: ${e.message}")
            org.json.JSONObject()
                .put("error", "Sub-agent '$agentType' failed: ${e.message}. Handle the task yourself with the tools above.")
                .toString()
        } finally {
            // Release the sub-agent's own provider bindings; nothing else does it.
            subRegistry?.unbindAll()
        }
    }

    suspend fun getAvailableTools(): String {
        return toolRegistry.discoverAndBindTools()
    }

    fun startTask(request: String) {
        if (purging.get()) {
            forensicLogger.logEvent("TASK_REJECTED", "Purge & Stop in progress; request ignored: $request")
            return
        }
        if (!taskRunning.compareAndSet(false, true)) {
            forensicLogger.logEvent("TASK_REJECTED", "A task is already running; new request ignored: $request")
            return
        }
        _taskRunning.value = true
        forensicLogger.logEvent("TASK_START", "Received user request: $request")
        // LAZY + register + start: a purge arriving between launch() and the field
        // write would otherwise cancel a job the field never saw.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val thisJob = coroutineContext[Job]
            try {
                // The transcript and history vectors persist across requests
                // so follow-up questions keep their context; the user clears
                // them explicitly with the Clear button (clearLog).
                // flushLog REPLACES the stored file, so append to the loaded
                // history instead of flushing a single-event list.
                val history = sessionPersistence.loadLog()
                history.add(SessionEvent("user", request))
                sessionPersistence.flushLog(history)

                val result = agentLoop.runTask(request)
                forensicLogger.logEvent("TASK_END", "Task completed with result: $result")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stop/Purge must look like a cancellation, not a task failure —
                // rethrow so the coroutine ends as cancelled.
                forensicLogger.logEvent("TASK_STOPPED", "Task cancelled: $request")
                throw e
            } catch (e: Exception) {
                // An uncaught failure here (e.g. SecurityException when no LLM provider
                // is installed) would crash the whole process instead of failing the task.
                forensicLogger.logEvent("TASK_ERROR", "Task failed: ${e.message}")
            } finally {
                // NON-SUSPENDING ONLY: this block runs while unwinding a cancelled
                // coroutine; any suspend call here would need withContext(NonCancellable).
                // Resetting the flag here is what lets a new task start after a Stop.
                taskRunning.set(false)
                _taskRunning.value = false
                taskJob.compareAndSet(thisJob, null)
            }
        }
        taskJob.set(job)
        job.start()
    }

    /**
     * Purge & Stop: cancels the running task, releases every tool-provider binder
     * and wipes all persisted or in-memory agent state. The forensic log is the ONE
     * thing never touched here (ForensicLogger has no purge API on purpose), and the
     * LLM configuration (SharedPreferences llm_config) is left intact.
     */
    fun purgeAndStopAll() {
        if (!purging.compareAndSet(false, true)) {
            forensicLogger.logEvent("PURGE_SKIPPED", "A purge is already running")
            return
        }
        scope.launch {
            try {
                forensicLogger.logEvent("PURGE_START", "stop + full purge requested")

                // 1. Stop the task first: nothing may keep writing while we delete.
                val job = taskJob.getAndSet(null)
                if (job != null) {
                    job.cancel()
                    // The LLM HTTP call is a blocking HttpURLConnection, so
                    // cancellation can only land once it returns; never wait for
                    // that longer than this. A draining task cannot write state
                    // afterwards: every write path is a cancellable suspend entry,
                    // and AgentLoop re-checks ensureActive() before running tools.
                    val joined = kotlinx.coroutines.withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { job.join() }
                    if (joined == null) {
                        forensicLogger.logEvent("TASK_STOP_TIMEOUT", "task still draining an LLM call after ${STOP_JOIN_TIMEOUT_MS}ms")
                    }
                }
                taskRunning.set(false)
                _taskRunning.value = false

                // 2. Release tool-provider bindings.
                toolRegistry.unbindAll()

                // 3. Transcript: in-memory flow + session_1.jsonl.gz (empties _uiState).
                sessionPersistence.clearLog()

                // 4. Sub-task session files (never deleted before this feature).
                val subtasks = SessionPersistence.deleteSubtaskSessions(java.io.File(filesDir, "sessions"))

                // 5. Vectors: history chunks AND durable kind="memory" facts.
                val vectors = vectorStore?.purgeAll() ?: 0

                // 6. Embedder ingest cursor + in-memory AgentLoop state.
                agentLoop.resetContext()

                // 7. Clarification records on disk.
                clarificationStore.clear()

                // 8. Approved (tool, package) permissions.
                val approvals = interactionManager.resetApprovedTools()

                // 9. Orphaned deferreds: complete BEFORE clearing so a still-draining
                //    task resumes (denied / neutral) instead of awaiting a dead dialog.
                val perms = permissionResponses.values.toList()
                permissionResponses.clear()
                perms.forEach { it.complete(false) }
                val clars = clarificationResponses.values.toList()
                clarificationResponses.clear()
                clars.forEach { it.complete("") }

                _purgeEpoch.value = _purgeEpoch.value + 1
                forensicLogger.logEvent("FULL_PURGE", "subtask_files=$subtasks vectors=$vectors approved_permissions=$approvals")
            } catch (e: Exception) {
                forensicLogger.logEvent("PURGE_ERROR", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                taskRunning.set(false)
                _taskRunning.value = false
                purging.set(false)
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

        // No human answer in time: fall back to the caller-provided default. An
        // LLM-invented answer used to pollute the context with a fabricated reply
        // (e.g. inventing the sender of an email the agent was asked to read), so
        // the neutral notice below keeps the task moving without misleading it.
        val finalChoice = effectiveDefault

        val chosenAnswer = finalChoice ?: "No clarification was provided by the human."
        forensicLogger.logEvent("INPUT_TIMEOUT", "No human answer; using ${if (effectiveDefault != null) "default" else "neutral notice"}: $chosenAnswer")
        clarificationStore.save(ClarificationRecord(
            id = requestId,
            prompt = request.prompt,
            defaultAnswer = request.defaultAnswer,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + request.timeoutMs,
            answeredAt = System.currentTimeMillis(),
            finalAnswer = chosenAnswer,
            source = if (effectiveDefault != null) "default" else "timeout"
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
