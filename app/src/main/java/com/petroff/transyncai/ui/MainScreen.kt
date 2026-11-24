package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.config.LanguagePair
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: SessionUiState,
    currentLanguagePair: LanguagePair,
    currentConversationContext: String,
    onToggleListening: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveConversationContext: (String) -> Unit,
    // Both/Translation need Claude to actually translate; Transcription doesn't. Defaulted so
    // call sites that don't care about this axis (most existing tests) don't need updating.
    hasApiKey: Boolean = true,
    onApiKeyRequired: () -> Unit = {},
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
                title = { Text("TransyncAI") },
                actions = {
                    // Hidden when there's no translation panel to provide context for,
                    // disabled while listening (same rule as the view-mode switch below:
                    // context only takes effect at the start of a session).
                    if (uiState.viewMode != ViewMode.TRANSCRIPTION_ONLY) {
                        IconButton(onClick = { showContextDialog = true }, enabled = !uiState.isListening) {
                            Icon(Icons.AutoMirrored.Filled.SpeakerNotes, contentDescription = "Context")
                        }
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
            // Read-only here — editable in Settings (language switching mid-session isn't a
            // common flow, and it's disabled while listening anyway; see below).
            Text(
                "${currentLanguagePair.sourceName} → ${currentLanguagePair.targetName}",
                style = MaterialTheme.typography.labelLarge,
            )

            // Disabled while listening: SessionViewModel's translate-gate (Task 8) checks
            // viewMode at segment-arrival time, not whether a key was present when the
            // session started — switching into Both/Translation-only mid-session with no
            // key set would surface a (harmless, no-network-call) error rather than being
            // caught proactively. Locking the switch while isListening closes that gap at
            // the UI layer instead of reworking already-reviewed ViewModel logic.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ViewMode.entries.forEachIndexed { index, mode ->
                    // Both/Translation need an Anthropic API key to do anything useful; Transcription
                    // doesn't. Rather than a real Compose-disabled state (which would swallow
                    // the tap), these stay clickable but faded, and redirect to Settings with
                    // the key field focused — a real disabled state can't route a click anywhere.
                    val missingApiKey = mode != ViewMode.TRANSCRIPTION_ONLY && !hasApiKey
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

            // Bounded to the remaining space (weight(1f)) rather than growing with the
            // Column: long transcripts/translations used to push content off-screen with
            // no way to see it. Each panel scrolls independently and auto-scrolls to the
            // latest text as it grows. Stacked top/bottom (not side-by-side) so full
            // sentences stay readable on a phone-width screen; when only one panel is
            // visible (Transcription/Translation-only mode) its weight(1f) lets it fill
            // all the remaining space.
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (uiState.viewMode != ViewMode.TRANSLATION_ONLY) {
                    TextPanel(
                        label = "Source",
                        text = uiState.sourceText,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                // Only between the two panels — nothing to separate in a single-panel view mode.
                if (uiState.viewMode == ViewMode.BOTH) {
                    HorizontalDivider(modifier = Modifier.testTag("sourceTranslationDivider"))
                }
                if (uiState.viewMode != ViewMode.TRANSCRIPTION_ONLY) {
                    TextPanel(
                        label = "Translation",
                        text = uiState.translatedText,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPanel(label: String, text: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        )
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
        title = { Text("Conversation context") },
        text = {
            Column {
                Text(
                    "Leave empty to use your general context from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextField(
                    value = contextField,
                    onValueChange = { contextField = it },
                    label = { Text("Conversation context") },
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

private fun ViewMode.label(): String = when (this) {
    ViewMode.BOTH -> "Both"
    ViewMode.TRANSCRIPTION_ONLY -> "Transcription"
    ViewMode.TRANSLATION_ONLY -> "Translation"
}
