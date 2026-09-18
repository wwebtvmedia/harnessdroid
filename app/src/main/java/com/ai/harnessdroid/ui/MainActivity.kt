package com.ai.harnessdroid.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import com.ai.harnessdroid.R
import com.ai.harnessdroid.core.AgentLoopSettings
import com.ai.harnessdroid.core.SessionEvent
import com.ai.harnessdroid.core.HarnessService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private var harnessServiceState = mutableStateOf<HarnessService?>(null)
    private var isBound = false
    private var pendingPrompt: String? = null

    /** Text zoom factor (sp multiplier); survives restarts via UiZoom prefs. */
    private var textScale = mutableStateOf(1f)

    /** Pins the per-app UI language (LocaleManager); "" follows the system. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HarnessService.LocalBinder
            harnessServiceState.value = binder.getService()
            isBound = true
            maybeRunPendingTask()
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            harnessServiceState.value = null
            isBound = false
        }
    }

    private fun maybeRunPendingTask() {
        val prompt = pendingPrompt ?: return
        harnessServiceState.value?.startTask(prompt)
        pendingPrompt = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textScale.value = UiZoom.load(this)

        val incomingPrompt = intent?.getStringExtra("prompt") ?: intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (!incomingPrompt.isNullOrBlank()) {
            pendingPrompt = incomingPrompt
        }

        applyRemoteConfigIfPresent(intent)

        Intent(this, HarnessService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            startService(intent)
        }

        setContent {
            val density = LocalDensity.current
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4CAF50), background = Color(0xFF121212), surface = Color(0xFF1E1E1E))) {
                // Scaling fontScale (not density) zooms every `sp` text in the
                // whole screen — chat, input, buttons — while dp metrics and
                // layout stay untouched. key(textScale) replaces the subtree
                // when the zoom changes: skippable composables (chips, app bar
                // buttons) would otherwise keep their stale captured density.
                key(textScale.value) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale * textScale.value)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            HarnessScreen(
                                harnessServiceState.value,
                                onTextZoomChange = { factor -> changeTextZoom(factor) }
                            )
                        }
                    }
                }
            }
        }
    }

    /** Multiplies the current text scale by [factor], clamped and persisted. */
    private fun changeTextZoom(factor: Float) {
        val newScale = (textScale.value * factor).coerceIn(UiZoom.MIN, UiZoom.MAX)
        if (newScale != textScale.value) {
            textScale.value = newScale
            UiZoom.save(this, newScale)
        }
    }

    /**
     * Applies custom_url/custom_api_key/custom_api_type launch extras to the LLM config.
     * Intents redelivered from the Recents/history list are ignored: re-applying a stale
     * remote configuration would silently switch the agent away from the LLMProvider the
     * user selected in the settings dialog.
     */
    private fun applyRemoteConfigIfPresent(intent: Intent?) {
        val fromHistory = intent != null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (fromHistory) return

        val customUrl = intent?.getStringExtra("custom_url")
        val customApiKey = intent?.getStringExtra("custom_api_key")
        val customApiType = intent?.getStringExtra("custom_api_type")
        val customModel = intent?.getStringExtra("custom_model")

        if (!customUrl.isNullOrBlank() || !customApiKey.isNullOrBlank() || !customApiType.isNullOrBlank() || !customModel.isNullOrBlank()) {
            val configManager = com.ai.harnessdroid.llm.LLMConfigManager(this)
            configManager.useTree4Five = false
            if (!customUrl.isNullOrBlank()) configManager.customUrl = customUrl
            if (!customApiKey.isNullOrBlank()) configManager.customApiKey = customApiKey
            if (!customApiType.isNullOrBlank()) configManager.customApiType = customApiType
            if (!customModel.isNullOrBlank()) configManager.customModel = customModel
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With launchMode="singleTop", a second launch intent (e.g. an external automation
        // passing a prompt) arrives here instead of recreating the activity.
        val incomingPrompt = intent.getStringExtra("prompt") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        applyRemoteConfigIfPresent(intent)
        if (!incomingPrompt.isNullOrBlank()) {
            pendingPrompt = incomingPrompt
            maybeRunPendingTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessScreen(
    harnessService: HarnessService?,
    onTextZoomChange: ((factor: Float) -> Unit)? = null
) {
    var inputText by remember { mutableStateOf("") }
    var showPlanMenu by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }

    val chatLog = harnessService?.uiState?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val forensicLog = harnessService?.forensicState?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val taskRunning = harnessService?.taskRunningState?.collectAsState(initial = false)?.value ?: false
    val purgeEpoch = harnessService?.purgeEpoch?.collectAsState(initial = 0)?.value ?: 0
    var showPurgeConfirm by remember { mutableStateOf(false) }

    var activePermissionRequest by remember { mutableStateOf<com.ai.harnessdroid.core.PermissionRequest?>(null) }
    var activeClarificationRequest by remember { mutableStateOf<com.ai.harnessdroid.core.ClarificationRequest?>(null) }
    var clarificationInput by remember { mutableStateOf("") }

    LaunchedEffect(harnessService) {
        if (harnessService == null) return@LaunchedEffect
        launch {
            harnessService.permissionRequests.collect { req ->
                activePermissionRequest = req
            }
        }
        launch {
            harnessService.clarificationRequests.collect { req ->
                activeClarificationRequest = req
                clarificationInput = req.defaultAnswer ?: ""
            }
        }
    }

    // After a Purge & Stop, drop permission/clarification dialogs whose
    // deferred was just completed-and-cleared by the purge.
    LaunchedEffect(purgeEpoch) {
        if (purgeEpoch > 0) {
            activePermissionRequest = null
            activeClarificationRequest = null
            clarificationInput = ""
        }
    }

    if (showPurgeConfirm) {
        PurgeConfirmDialog(
            running = taskRunning,
            onConfirm = {
                showPurgeConfirm = false
                harnessService?.purgeAndStopAll()
            },
            onDismiss = { showPurgeConfirm = false }
        )
    }

    activePermissionRequest?.let { req ->
        PermissionPopup(
            toolName = req.toolName,
            reason = req.reason,
            onApprove = {
                harnessService?.providePermissionResponse(req.id, true)
                activePermissionRequest = null
            },
            onDeny = {
                harnessService?.providePermissionResponse(req.id, false)
                activePermissionRequest = null
            }
        )
    }

    activeClarificationRequest?.let { req ->
        ClarificationPopup(
            prompt = req.prompt,
            defaultAnswer = req.defaultAnswer,
            value = clarificationInput,
            onValueChange = { clarificationInput = it },
            onSubmit = {
                harnessService?.provideClarificationResponse(req.id, clarificationInput)
                activeClarificationRequest = null
                clarificationInput = ""
            },
            onUseDefault = {
                harnessService?.provideClarificationResponse(req.id, req.defaultAnswer ?: "")
                activeClarificationRequest = null
                clarificationInput = ""
            },
            onDismiss = {
                harnessService?.provideClarificationResponse(req.id, req.defaultAnswer ?: "")
                activeClarificationRequest = null
                clarificationInput = ""
            }
        )
    }

    if (showPlanMenu) {
        PlanDialog(chatLog = chatLog, onDismiss = { showPlanMenu = false })
    }

    if (showDebugMenu) {
        SystemMessageDialog(forensicLog = forensicLog, onDismiss = { showDebugMenu = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                actions = {
                    // Text zoom controls: A- shrinks, A+ grows (persisted).
                    IconButton(
                        onClick = { onTextZoomChange?.invoke(1f / UiZoom.BUTTON_STEP) },
                        modifier = Modifier.testTag("zoom_out")
                    ) {
                        Text("A-", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { onTextZoomChange?.invoke(UiZoom.BUTTON_STEP) },
                        modifier = Modifier.testTag("zoom_in")
                    ) {
                        Text("A+", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    var showToolsDialog by remember { mutableStateOf(false) }
                    var toolsJson by remember { mutableStateOf("[]") }
                    val scope = rememberCoroutineScope()

                    Button(onClick = {
                        scope.launch {
                            val tJson = harnessService?.getAvailableTools() ?: "[]"
                            withContext(Dispatchers.Main) {
                                toolsJson = tJson
                                showToolsDialog = true
                            }
                        }
                    }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(stringResource(R.string.btn_list_tools))
                    }
                    Button(onClick = { harnessService?.clearLog() }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(stringResource(R.string.btn_clear))
                    }
                    Button(
                        onClick = { showPurgeConfirm = true },
                        modifier = Modifier.padding(end = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) {
                        Text(stringResource(R.string.btn_purge))
                    }
                    Button(onClick = { showDebugMenu = true }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(stringResource(R.string.btn_system_log))
                    }
                    Button(onClick = { showPlanMenu = true }) {
                        Text(stringResource(R.string.btn_view_plan))
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    var showLanguageMenu by remember { mutableStateOf(false) }
                    var showVersionDialog by remember { mutableStateOf(false) }
                    var showHelpDialog by remember { mutableStateOf(false) }
                    var showLLMConfigDialog by remember { mutableStateOf(false) }
                    var showMemoryDialog by remember { mutableStateOf(false) }
                    var showLoopSettingsDialog by remember { mutableStateOf(false) }

                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu))
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_help)) },
                            onClick = {
                                showMenu = false
                                showHelpDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_version)) },
                            onClick = {
                                showMenu = false
                                showVersionDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_llm_config)) },
                            onClick = {
                                showMenu = false
                                showLLMConfigDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_memory)) },
                            onClick = {
                                showMenu = false
                                showMemoryDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_loop_settings)) },
                            onClick = {
                                showMenu = false
                                showLoopSettingsDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_language)) },
                            onClick = {
                                showMenu = false
                                showLanguageMenu = true
                            }
                        )
                    }

                    // Language submenu, anchored like the main menu: switching
                    // persists the tag and recreates the activity so the whole
                    // composition re-resolves against the new locale.
                    if (showLanguageMenu) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val currentTag = remember { LocaleManager.load(context) }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lang_system)) },
                                leadingIcon = {
                                    if (currentTag.isEmpty()) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                onClick = {
                                    showLanguageMenu = false
                                    if (currentTag.isNotEmpty()) {
                                        LocaleManager.save(context, "")
                                        (context as? Activity)?.recreate()
                                    }
                                }
                            )
                            for (lang in LocaleManager.LANGUAGES) {
                                DropdownMenuItem(
                                    text = { Text(LocaleManager.nativeName(lang)) },
                                    leadingIcon = {
                                        if (currentTag == lang) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        showLanguageMenu = false
                                        if (currentTag != lang) {
                                            LocaleManager.save(context, lang)
                                            (context as? Activity)?.recreate()
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (showVersionDialog) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val versionName = try {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0).versionName ?: "?"
                        } catch (_: Exception) {
                            "?"
                        }
                        AlertDialog(
                            onDismissRequest = { showVersionDialog = false },
                            title = { Text(stringResource(R.string.menu_version)) },
                            text = { Text(stringResource(R.string.version_body, versionName)) },
                            confirmButton = {
                                Button(onClick = { showVersionDialog = false }) { Text(stringResource(R.string.btn_ok)) }
                            }
                        )
                    }

                    if (showLLMConfigDialog) {
                        LLMConfigDialog(
                            context = androidx.compose.ui.platform.LocalContext.current,
                            onDismiss = { showLLMConfigDialog = false }
                        )
                    }

                    if (showMemoryDialog) {
                        MemoryDialog(
                            harnessService = harnessService,
                            onDismiss = { showMemoryDialog = false }
                        )
                    }

                    if (showLoopSettingsDialog) {
                        LoopSettingsDialog(
                            context = androidx.compose.ui.platform.LocalContext.current,
                            onDismiss = { showLoopSettingsDialog = false }
                        )
                    }

                    if (showToolsDialog) {
                        ToolsDialog(toolsJson = toolsJson, onDismiss = { showToolsDialog = false })
                    }

                    if (showHelpDialog) {
                        AlertDialog(
                            onDismissRequest = { showHelpDialog = false },
                            title = { Text(stringResource(R.string.menu_help)) },
                            text = { Text(stringResource(R.string.help_body)) },
                            confirmButton = {
                                Button(onClick = { showHelpDialog = false }) { Text(stringResource(R.string.btn_ok)) }
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                // Two-finger pinch anywhere in the screen zooms the text; the
                // handler never consumes single-finger events, so the chat
                // list still scrolls normally.
                .textPinchZoom { factor -> onTextZoomChange?.invoke(factor) }
        ) {
            val examples = stringArrayResource(R.array.examples)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                examples.forEach { ex ->
                    AssistChip(onClick = { inputText = ex }, label = { Text(ex, maxLines = 1) })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(chatLog) { event ->
                    EventBubble(event)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).testTag("request_input"),
                    placeholder = { Text(stringResource(R.string.request_input_hint)) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    harnessService?.startTask(inputText)
                    inputText = ""
                }, enabled = harnessService != null, modifier = Modifier.testTag("run_button")) {
                    Text(stringResource(R.string.btn_run))
                }
            }
        }
    }
}

@Composable
fun PlanDialog(chatLog: List<SessionEvent>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_title)) },
        text = {
            val planSteps = chatLog.filter { it.role == "assistant" || it.role == "tool" || it.role == "system" }
            LazyColumn {
                items(planSteps) { step ->
                    Text(
                        text = "${step.role.uppercase()}: ${step.content}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = if (step.role == "assistant") Color(0xFF4CAF50) else Color.LightGray
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

@Composable
fun SystemMessageDialog(forensicLog: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.system_log_title)) },
        text = {
            LazyColumn {
                items(forensicLog) { logLine ->
                    Text(
                        text = logLine,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = Color(0xFFB71C1C) // Red tinted for system logs
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

@Composable
fun EventBubble(event: SessionEvent) {
    val backgroundColor = when (event.role) {
        "user" -> Color(0xFF2E7D32)
        "tool" -> Color(0xFF424242)
        "assistant" -> Color(0xFF1E1E1E)
        "system" -> Color(0xFFB71C1C)
        else -> Color.Gray
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = event.role.uppercase() + (if (event.toolName != null) " [${event.toolName}]" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = event.content, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

@Composable
fun PermissionPopup(toolName: String, reason: String, onApprove: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.permission_title)) },
        text = { Text(stringResource(R.string.permission_message, toolName, reason)) },
        confirmButton = {
            Button(onClick = onApprove) { Text(stringResource(R.string.btn_allow)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.btn_deny)) }
        }
    )
}

@Composable
fun PurgeConfirmDialog(running: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btn_purge)) },
        text = {
            Text(
                // Two full sentences per locale (never a concatenated prefix:
                // aapt2 strips trailing whitespace and word order differs).
                stringResource(
                    if (running) R.string.purge_confirm_body_running
                    else R.string.purge_confirm_body
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
            ) { Text(stringResource(R.string.btn_purge_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
fun ClarificationPopup(
    prompt: String,
    defaultAnswer: String?,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clarification_title)) },
        text = {
            Column {
                Text(prompt)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(defaultAnswer ?: stringResource(R.string.clarification_hint)) }
                )
            }
        },
        confirmButton = {
            Button(onClick = onSubmit) { Text(stringResource(R.string.btn_submit)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!defaultAnswer.isNullOrBlank()) {
                    OutlinedButton(onClick = onUseDefault) { Text(stringResource(R.string.btn_use_default)) }
                }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.btn_ignore)) }
            }
        }
    )
}

@Composable
fun ToolsDialog(toolsJson: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tools_title)) },
        text = {
            // stringResource calls stay in this composable scope — inside
            // LazyColumn's LazyListScope DSL they are not allowed.
            val unknown = stringResource(R.string.tools_unknown_name)
            val noDescription = stringResource(R.string.tools_no_description)
            LazyColumn {
                val toolsArray = try {
                    org.json.JSONArray(toolsJson)
                } catch (e: Exception) {
                    org.json.JSONArray()
                }

                val count = toolsArray.length()
                if (count == 0) {
                    item { Text(stringResource(R.string.tools_none)) }
                } else {
                    for (i in 0 until count) {
                        val tool = toolsArray.getJSONObject(i)
                        val name = tool.optString("name", unknown)
                        val desc = tool.optString("description", noDescription)
                        item {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium, color = Color(0xFF4CAF50))
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

@Composable
fun LoopSettingsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    var maxTurnsText by remember { mutableStateOf(AgentLoopSettings.load(context).toString()) }
    val parsed = maxTurnsText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_loop_settings)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.loop_description,
                        AgentLoopSettings.MIN, AgentLoopSettings.MAX, AgentLoopSettings.DEFAULT
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxTurnsText,
                    // Digits only, kept short: the field can never hold a value
                    // big enough to overflow an Int on parse.
                    onValueChange = { maxTurnsText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.loop_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("loop_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    AgentLoopSettings.save(context, parsed ?: AgentLoopSettings.DEFAULT)
                    onDismiss()
                },
                // Out-of-range values are clamped by save(); a blank field is not savable.
                enabled = parsed != null
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
fun LLMConfigDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val configManager = remember { com.ai.harnessdroid.llm.LLMConfigManager(context) }
    var useTree4Five by remember { mutableStateOf(configManager.useTree4Five) }
    var customUrl by remember { mutableStateOf(configManager.customUrl) }
    var customApiKey by remember { mutableStateOf(configManager.customApiKey) }
    var customApiType by remember { mutableStateOf(configManager.customApiType) }
    var customModel by remember { mutableStateOf(configManager.customModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_llm_config)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = useTree4Five,
                        onClick = { useTree4Five = true }
                    )
                    Text(stringResource(R.string.llm_tree4five))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !useTree4Five,
                        onClick = { useTree4Five = false }
                    )
                    Text(stringResource(R.string.llm_custom))
                }

                if (!useTree4Five) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text(stringResource(R.string.llm_api_url)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        label = { Text(stringResource(R.string.llm_api_key)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiType,
                        onValueChange = { customApiType = it },
                        label = { Text(stringResource(R.string.llm_api_type)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text(stringResource(R.string.llm_model_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                configManager.useTree4Five = useTree4Five
                configManager.customUrl = customUrl
                configManager.customApiKey = customApiKey
                configManager.customApiType = customApiType
                configManager.customModel = customModel
                onDismiss()
            }) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
fun MemoryDialog(
    harnessService: HarnessService?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val memoryService = harnessService?.memoryService
    val vectorStore = harnessService?.vectorStore

    // Status messages are captured in composition: the onClick handlers run
    // outside recomposition and stringResource() is composable-only.
    val storedMsg = stringResource(R.string.memory_status_stored)
    val storeFailedMsg = stringResource(R.string.memory_status_store_failed)
    val recallEmptyMsg = stringResource(R.string.memory_status_recall_empty)

    // Facts are re-read from the store after every mutation.
    var facts by remember { mutableStateOf(listOf<String>()) }
    var newFact by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var recallResults by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("") }

    fun refresh() {
        facts = vectorStore?.allEntries("memory")?.map { it.text } ?: emptyList()
    }
    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_memory)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.memory_description),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFact,
                        onValueChange = { newFact = it },
                        label = { Text(stringResource(R.string.memory_new_fact)) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val fact = newFact
                            scope.launch {
                                val stored = memoryService?.remember(fact) ?: false
                                status = if (stored) storedMsg else storeFailedMsg
                                newFact = ""
                                refresh()
                            }
                        },
                        enabled = newFact.isNotBlank() && memoryService != null
                    ) {
                        Text(stringResource(R.string.btn_remember))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.memory_recall_query)) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val q = query
                            scope.launch {
                                recallResults = memoryService?.recall(q) ?: emptyList()
                                status = if (recallResults.isEmpty()) recallEmptyMsg else ""
                            }
                        },
                        enabled = query.isNotBlank() && memoryService != null
                    ) {
                        Text(stringResource(R.string.btn_recall))
                    }
                }

                if (status.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(status, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }

                if (recallResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.memory_recalled), fontWeight = FontWeight.Bold)
                    recallResults.forEach { Text("- $it", maxLines = 2) }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.memory_facts_count, facts.size), fontWeight = FontWeight.Bold)
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(facts.size) { i ->
                        Text("- ${facts[i]}", maxLines = 2)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}
