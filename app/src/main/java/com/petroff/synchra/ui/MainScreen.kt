package com.petroff.synchra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.MAX_CONVERSATION_CONTEXT_CHARS
import com.petroff.synchra.session.SessionUiState
import com.petroff.synchra.session.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: SessionUiState,
    sourceLanguage: Language,
    targetLanguage: Language,
    currentConversationContext: String,
    onToggleListening: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveConversationContext: (String) -> Unit,
    // Every mode needs the Soniox key (it transcribes and translates). Defaulted so call sites
    // that don't care about this axis (most existing tests) don't need updating.
    hasApiKey: Boolean = true,
    onApiKeyRequired: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onSummary: () -> Unit = {},
) {
    var showContextDialog by remember { mutableStateOf(false) }

    if (showContextDialog) {
        ContextDialog(
            currentConversationContext = currentConversationContext,
            onSave = {
                onSaveConversationContext(it)
                showContextDialog = false
            },
            onDismiss = { showContextDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The brand: the buttons' colour and its own typeface, set apart from screen titles.
                    Text(
                        "Synchra",
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("appTitle"),
                    )
                },
                actions = {
                    // Saved conversations: a block of its own, set apart from the session controls.
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    // Summarizes the conversation the last Stop saved; nothing to summarize mid-session.
                    IconButton(onClick = onSummary, enabled = !uiState.isListening && uiState.lastRecordId != null) {
                        Icon(Icons.Filled.Summarize, contentDescription = "Summary")
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(horizontal = 4.dp)
                            .testTag("historySummaryDivider"),
                    )
                    // Context helps Soniox recognize and translate, in every view mode. Disabled
                    // while listening (same rule as the view-mode switch below: context only
                    // takes effect at the start of a session).
                    IconButton(onClick = { showContextDialog = true }, enabled = !uiState.isListening) {
                        Icon(Icons.AutoMirrored.Filled.SpeakerNotes, contentDescription = "Context")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Chosen in Settings; a recording keeps the languages it started with.
            Text("${sourceLanguage.name} → ${targetLanguage.name}", style = MaterialTheme.typography.labelLarge)

            // Only what is shown: works while recording too. Whether Soniox translates is
            // decided at Start (see LiveSession.start), so the bill doesn't change mid-session.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ViewMode.entries.forEachIndexed { index, mode ->
                    // Without the Soniox key nothing works. Rather than a real Compose-disabled
                    // state (which would swallow the tap), the modes stay clickable but faded,
                    // and redirect to Settings with the key field focused — a real disabled
                    // state can't route a click anywhere.
                    val missingApiKey = !hasApiKey
                    SegmentedButton(
                        selected = uiState.viewMode == mode,
                        onClick = { if (missingApiKey) onApiKeyRequired() else onViewModeChange(mode) },
                        colors = if (missingApiKey) {
                            SegmentedButtonDefaults.colors(
                                inactiveContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        } else {
                            SegmentedButtonDefaults.colors()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ViewMode.entries.size),
                    ) {
                        Text(mode.label())
                    }
                }
            }

            Button(
                onClick = onToggleListening,
                enabled = uiState.isListening || uiState.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.isListening) "Stop" else "Start")
            }

            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            TranscriptPanels(
                viewMode = uiState.viewMode,
                sourceText = uiState.sourceText,
                translatedText = uiState.translatedText,
                translationNote = if (uiState.translationRequested) null
                else "No translation: this recording was started in Transcription mode.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ContextDialog(
    currentConversationContext: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var contextField by remember { mutableStateOf(currentConversationContext) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About this conversation") },
        text = {
            Column {
                Text(
                    "Topic, participants and terms of this conversation. Sent together with " +
                        "the global context from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextField(
                    value = contextField,
                    onValueChange = { contextField = it.take(MAX_CONVERSATION_CONTEXT_CHARS) },
                    label = { Text("About this conversation") },
                    placeholder = { Text("e.g. Sprint planning with John and Maria, about the checkout API") },
                    supportingText = { Text("${contextField.length} / $MAX_CONVERSATION_CONTEXT_CHARS") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(contextField) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

internal fun ViewMode.label(): String = when (this) {
    ViewMode.BOTH -> "Both"
    ViewMode.TRANSCRIPTION_ONLY -> "Transcription"
    ViewMode.TRANSLATION_ONLY -> "Translation"
}
