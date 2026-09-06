package com.ai.harnessdroid.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.ai.harnessdroid.core.SessionEvent
import com.ai.harnessdroid.core.HarnessService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope

class MainActivity : ComponentActivity() {
    private var harnessServiceState = mutableStateOf<HarnessService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HarnessService.LocalBinder
            harnessServiceState.value = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            harnessServiceState.value = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Intent(this, HarnessService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            startService(intent)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4CAF50), background = Color(0xFF121212), surface = Color(0xFF1E1E1E))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen(harnessServiceState.value)
                }
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessScreen(harnessService: HarnessService?) {
    var inputText by remember { mutableStateOf("") }
    var showPlanMenu by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }
    
    val chatLog = harnessService?.uiState?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val forensicLog = harnessService?.forensicState?.collectAsState(initial = emptyList())?.value ?: emptyList()
    
    var activePermissionRequest by remember { mutableStateOf<com.ai.harnessdroid.core.PermissionRequest?>(null) }
    
    LaunchedEffect(harnessService) {
        harnessService?.permissionRequests?.collect { req ->
            activePermissionRequest = req
        }
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

    if (showPlanMenu) {
        PlanDialog(chatLog = chatLog, onDismiss = { showPlanMenu = false })
    }

    if (showDebugMenu) {
        SystemMessageDialog(forensicLog = forensicLog, onDismiss = { showDebugMenu = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tree4Five Harness", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                actions = {
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
                        Text("List Tools")
                    }
                    Button(onClick = { harnessService?.clearLog() }, modifier = Modifier.padding(end = 4.dp)) {
                        Text("Clear")
                    }
                    Button(onClick = { showDebugMenu = true }, modifier = Modifier.padding(end = 4.dp)) {
                        Text("System Log")
                    }
                    Button(onClick = { showPlanMenu = true }) {
                        Text("View Plan")
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    var showVersionDialog by remember { mutableStateOf(false) }
                    var showHelpDialog by remember { mutableStateOf(false) }
                    var showLLMConfigDialog by remember { mutableStateOf(false) }

                    IconButton(onClick = { showMenu = true }) {
                        Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Menu")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Help") },
                            onClick = { 
                                showMenu = false
                                showHelpDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Version") },
                            onClick = { 
                                showMenu = false
                                showVersionDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("LLM Configuration") },
                            onClick = { 
                                showMenu = false
                                showLLMConfigDialog = true
                            }
                        )
                    }

                    if (showVersionDialog) {
                        AlertDialog(
                            onDismissRequest = { showVersionDialog = false },
                            title = { Text("Version") },
                            text = { Text("Tree4Five Harness v1.0.0") },
                            confirmButton = {
                                Button(onClick = { showVersionDialog = false }) { Text("OK") }
                            }
                        )
                    }

                    if (showLLMConfigDialog) {
                        LLMConfigDialog(
                            context = androidx.compose.ui.platform.LocalContext.current,
                            onDismiss = { showLLMConfigDialog = false }
                        )
                    }

                    if (showToolsDialog) {
                        ToolsDialog(toolsJson = toolsJson, onDismiss = { showToolsDialog = false })
                    }

                    if (showHelpDialog) {
                        AlertDialog(
                            onDismissRequest = { showHelpDialog = false },
                            title = { Text("Help") },
                            text = { Text("Welcome to Tree4Five Harness.\n\nType a request in the input field to interact with the LLM agent. You can view logs, tools, and the agent's plan using the top bar buttons.") },
                            confirmButton = {
                                Button(onClick = { showHelpDialog = false }) { Text("OK") }
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            val examples = listOf("Summarize emails", "Turn off lights", "Search deepseek harness")
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
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter request...") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { 
                    harnessService?.startTask(inputText)
                    inputText = ""
                }, enabled = harnessService != null) {
                    Text("Run")
                }
            }
        }
    }
}

@Composable
fun PlanDialog(chatLog: List<SessionEvent>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent Execution Plan") },
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
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SystemMessageDialog(forensicLog: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System/Forensic Logs") },
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
            Button(onClick = onDismiss) { Text("Close") }
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
        title = { Text("Permission Required") },
        text = { Text("The agent wants to use the tool '$toolName'.\nReason: $reason\nDo you allow this?") },
        confirmButton = {
            Button(onClick = onApprove) { Text("Allow") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDeny) { Text("Deny") }
        }
    )
}

@Composable
fun ToolsDialog(toolsJson: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available Tools") },
        text = {
            LazyColumn {
                val toolsArray = try {
                    org.json.JSONArray(toolsJson)
                } catch (e: Exception) {
                    org.json.JSONArray()
                }
                
                val count = toolsArray.length()
                if (count == 0) {
                    item { Text("No tools found.") }
                } else {
                    for (i in 0 until count) {
                        val tool = toolsArray.getJSONObject(i)
                        val name = tool.optString("name", "Unknown")
                        val desc = tool.optString("description", "No description")
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
            Button(onClick = onDismiss) { Text("Close") }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM Configuration") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = useTree4Five,
                        onClick = { useTree4Five = true }
                    )
                    Text("Tree4Five LLMProvider (Default)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !useTree4Five,
                        onClick = { useTree4Five = false }
                    )
                    Text("Custom LLM")
                }
                
                if (!useTree4Five) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("API URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiType,
                        onValueChange = { customApiType = it },
                        label = { Text("API Type (OpenAI, Gemini, etc.)") },
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
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
