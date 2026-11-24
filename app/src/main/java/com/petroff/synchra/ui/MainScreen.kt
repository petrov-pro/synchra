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
import androidx.compose.ui.unit.dp
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
    recentLanguages: List<Language> = emptyList(),
    onSourceLanguageChange: (Language) -> Unit = {},
    onTargetLanguageChange: (Language) -> Unit = {},
    onSwapLanguages: () -> Unit = {},
) {
    var showContextDialog by remember { mutableStateOf(false) }
    // Which side's picker is open: true = source, false = target, null = none.
    var pickingSource by remember { mutableStateOf<Boolean?>(null) }

    pickingSource?.let { isSource ->
        LanguagePickerSheet(
            title = if (isSource) "Speech language" else "Translate into",
            selected = if (isSource) sourceLanguage else targetLanguage,
            recent = recentLanguages,
            onPick = {
                if (isSource) onSourceLanguageChange(it) else onTargetLanguageChange(it)
                pickingSource = null
            },
            onDismiss = { pickingSource = null },
        )
    }

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
                title = { Text("Synchra") },
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
            // Languages are fixed when a session starts, so they can't change while listening.
            LanguageBar(
                source = sourceLanguage,
                target = targetLanguage,
                enabled = !uiState.isListening,
                onPickSource = { pickingSource = true },
                onPickTarget = { pickingSource = false },
                onSwap = onSwapLanguages,
            )

            // Disabled while listening: SessionViewModel's translate-gate (Task 8) checks
            // viewMode at segment-arrival time, not whether a key was present when the
            // session started — switching into Both/Translation-only mid-session with no
            // key set would surface a (harmless, no-network-call) error rather than being
            // caught proactively. Locking the switch while isListening closes that gap at
            // the UI layer instead of reworking already-reviewed ViewModel logic.
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
                        enabled = !uiState.isListening,
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
