package com.tree4five.harness.ui

import android.os.Bundle
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
import com.tree4five.harness.core.SessionEvent

/**
 * Professional Frontend for harnessDroid.
 * Matches the Tree4Five LLM Provider look and feel (Material 3).
 * Visualizes the thinking mechanism, plans, and tools used.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4CAF50), // Tree4Five Green
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen()
                }
            }
        }
    }
}

@Composable
fun HarnessScreen() {
    var inputText by remember { mutableStateOf("") }
    
    // Mocking the state that would come from HarnessService.uiState
    val chatLog = remember { mutableStateListOf<SessionEvent>() }
    
    // Examples provided for the user
    val examples = listOf(
        "Summarize my unread emails using the MailTool.",
        "Turn off the living room lights via SmartHomeTool.",
        "Analyze the system forensics log and find errors."
    )

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Tree4Five Harness", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        
        // Examples Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { ex ->
                AssistChip(
                    onClick = { inputText = ex },
                    label = { Text(ex, maxLines = 1) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Chat / Thinking Visualization Log
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(chatLog) { event ->
                EventBubble(event)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Input Area
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter request for the autonomous agent...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { 
                chatLog.add(SessionEvent("user", inputText))
                // Here we would call harnessService.startTask(inputText)
                inputText = ""
            }) {
                Text("Run")
            }
        }
    }
}

@Composable
fun EventBubble(event: SessionEvent) {
    val backgroundColor = when (event.role) {
        "user" -> Color(0xFF2E7D32)
        "tool" -> Color(0xFF424242)
        "assistant" -> Color(0xFF1E1E1E)
        "system" -> Color(0xFFB71C1C) // Forensics/Safety alerts
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

/**
 * Mechanism to popup a proposition for non-allowed tasks
 */
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
