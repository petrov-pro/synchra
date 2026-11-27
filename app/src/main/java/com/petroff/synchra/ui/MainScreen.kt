package com.petroff.synchra.ui

import com.petroff.synchra.config.DEFAULT_TEXT_SIZE_SP
import com.petroff.synchra.config.Mode

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import com.petroff.synchra.config.limitLabel
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.petroff.synchra.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.MAX_CONVERSATION_CONTEXT_CHARS
import com.petroff.synchra.config.shortPairLabel
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
    // Asked once, on the first start; the answer can be changed in Settings.
    askCrashReportsConsent: Boolean = false,
    onCrashReportsConsent: (Boolean) -> Unit = {},
    // Settings: a small icon in the text's corner while nobody speaks.
    showSilenceIcon: Boolean = true,
    // Settings: size of the live text.
    textSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
    // A session paused by the time limit: Continue listens on, Stop ends it.
    onContinueSession: () -> Unit = {},
    onStopPaused: () -> Unit = {},
) {
    var showContextDialog by remember { mutableStateOf(false) }

    uiState.pausedAfterMinutes?.let { minutes ->
        PausedDialog(minutes, onContinue = onContinueSession, onStop = onStopPaused)
    }

    if (askCrashReportsConsent) {
        CrashReportsConsentDialog(onAnswer = onCrashReportsConsent)
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
                    // Chosen in Settings; a recording keeps the languages it started with.
                    LanguagePairLabel(
                        shortPairLabel(sourceLanguage, targetLanguage),
                        sourceLanguage.name,
                        targetLanguage.name,
                    )
                    // Saved conversations: a block of its own, set apart from the session controls.
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    // Summarizes the conversation the last Stop saved; nothing to summarize mid-session.
                    // Free mode makes no summaries.
                    if (uiState.mode != Mode.FREE) {
                        IconButton(onClick = onSummary, enabled = !uiState.isListening && uiState.lastRecordId != null) {
                            Icon(Icons.Filled.Summarize, contentDescription = "Summary")
                        }
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
                    // Free mode's on-device engines take no context, so there is nothing to set.
                    if (uiState.mode != Mode.FREE) {
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
                // Tight above and between the controls: the room goes to the text.
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            if (!uiState.isListening && !uiState.canStart) {
                uiState.startHint?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (uiState.isPreparing) Text("Preparing translation…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TranscriptPanels(
                    viewMode = uiState.viewMode,
                    sourceText = uiState.sourceText,
                    translatedText = uiState.translatedText,
                    translationNote = if (uiState.translationRequested) null
                    else "No translation: this recording was started in Transcription mode.",
                    textSizeSp = textSizeSp,
                    modifier = Modifier.fillMaxSize(),
                )
                val silent = uiState.isListening && !uiState.isSending && !uiState.isPreparing
                if (showSilenceIcon && silent) {
                    Image(
                        painter = painterResource(R.drawable.ic_silence),
                        contentDescription = "Silence",
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .testTag("silenceIcon"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashReportsConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        // Not dismissible by tapping outside: the question needs an answer, either one.
        onDismissRequest = {},
        title = { Text("Send crash reports?") },
        text = {
            Text(
                "If Synchra crashes, it can send an anonymous report (device model, Android " +
                    "version and what the app was doing) so the bug can be fixed. Your " +
                    "conversations, contexts and API keys are never sent. You can change this " +
                    "in Settings.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text("Don't send") }
        },
    )
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

// Not dismissed by a tap outside or Back: the customer decides whether to keep paying.
@Composable
private fun PausedDialog(minutes: Int, onContinue: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Text("Paused after ${limitLabel(minutes)}. Continue?") },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Stop") } },
    )
}
