package com.petroff.synchra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.petroff.synchra.config.DEFAULT_SOURCE_LANGUAGE
import com.petroff.synchra.config.DEFAULT_TARGET_LANGUAGE
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.MAX_GENERAL_CONTEXT_CHARS
import com.petroff.synchra.config.languageForCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentSonioxApiKey: String?,
    currentApiKey: String?,
    currentGeneralContext: String,
    onSaveSonioxApiKey: (String) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveGeneralContext: (String) -> Unit,
    onBack: () -> Unit,
    // Set when the main screen redirected here because no Soniox key was set yet — most
    // callers (opening Settings via the gear icon) leave this false.
    autoFocusApiKey: Boolean = false,
    // Applied at once, like a switch; a recording keeps the languages it started with.
    sourceLanguage: Language = languageForCode(DEFAULT_SOURCE_LANGUAGE)!!,
    targetLanguage: Language = languageForCode(DEFAULT_TARGET_LANGUAGE)!!,
    recentLanguages: List<Language> = emptyList(),
    onSourceLanguageChange: (Language) -> Unit = {},
    onTargetLanguageChange: (Language) -> Unit = {},
    onSwapLanguages: () -> Unit = {},
) {
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

    var sonioxApiKeyField by remember { mutableStateOf(currentSonioxApiKey ?: "") }
    var apiKeyField by remember { mutableStateOf(currentApiKey ?: "") }
    var generalContextField by remember { mutableStateOf(currentGeneralContext) }
    var helpDialog by remember { mutableStateOf<HelpDialogContent?>(null) }

    val apiKeyFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    helpDialog?.let { content ->
        AlertDialog(
            onDismissRequest = { helpDialog = null },
            title = { Text(content.title) },
            text = { Text(content.text) },
            confirmButton = {
                TextButton(onClick = { helpDialog = null }) {
                    Text("Got it")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Must live inside Scaffold's content slot (a SubcomposeLayout, composed on a
            // deferred pass) rather than at the top of SettingsScreen — a LaunchedEffect
            // declared outside that slot can request focus before the TextField below is
            // actually attached, which silently no-ops instead of focusing it.
            LaunchedEffect(Unit) {
                if (autoFocusApiKey) {
                    apiKeyFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }

            FieldRow(
                field = "Languages",
                helpText = "The language that is spoken and the one it is translated into. Tap " +
                    "a language to search all 60; the arrows swap them. A recording already " +
                    "running keeps its languages.",
                onShowHelp = { helpDialog = it },
            ) {
                LanguageBar(
                    source = sourceLanguage,
                    target = targetLanguage,
                    enabled = true,
                    onPickSource = { pickingSource = true },
                    onPickTarget = { pickingSource = false },
                    onSwap = onSwapLanguages,
                )
            }

            FieldRow(
                field = "Soniox API key",
                helpText = "Your Soniox API key. Soniox recognizes your speech and translates it " +
                    "live (about \$0.12-0.23 per hour of audio). Required to start.",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = sonioxApiKeyField,
                    onValueChange = { sonioxApiKeyField = it },
                    label = { Text("Soniox API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(apiKeyFocusRequester),
                )
            }

            FieldRow(
                field = "Anthropic API key",
                helpText = "Your Anthropic (Claude) API key, used for conversation summaries. " +
                    "Live transcription and translation work without it.",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = apiKeyField,
                    onValueChange = { apiKeyField = it },
                    label = { Text("Anthropic API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Set apart from the connection/session fields above — general context is a
            // different kind of setting (standing background for the AI, not a connection or
            // session parameter) — with extra breathing room and a divider ahead of it.
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // Standing context, sent with every session ahead of the per-conversation one from the
            // Main screen's Context button — see LiveSession.start.
            FieldRow(
                field = "Global context",
                helpText = "Goes with every conversation: about you, your company, team, product, " +
                    "its names and terms. It helps Soniox recognize and translate, and Claude " +
                    "write the summary. Claude never treats it as something said: you may only be " +
                    "listening. Details of one conversation (topic, participants) go in the " +
                    "Context button on the main screen.",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = generalContextField,
                    onValueChange = { generalContextField = it.take(MAX_GENERAL_CONTEXT_CHARS) },
                    label = { Text("Global context") },
                    supportingText = { Text("${generalContextField.length} / $MAX_GENERAL_CONTEXT_CHARS") },
                    placeholder = { Text("e.g. I'm Andrey, a backend engineer on the payments team (Go, Kafka)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(onClick = {
                onSaveSonioxApiKey(sonioxApiKeyField)
                onSaveApiKey(apiKeyField)
                onSaveGeneralContext(generalContextField)
                onBack()
            }) {
                Text("Save")
            }
        }
    }
}

private data class HelpDialogContent(val title: String, val text: String)

/** A field plus its help icon, laid out consistently across every Settings row. */
@Composable
private fun FieldRow(
    field: String,
    helpText: String,
    onShowHelp: (HelpDialogContent) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            content()
        }
        IconButton(onClick = { onShowHelp(HelpDialogContent(field, helpText)) }) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help: $field")
        }
    }
}
