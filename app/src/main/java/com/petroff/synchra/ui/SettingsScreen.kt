package com.petroff.synchra.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.petroff.synchra.config.DEFAULT_SOURCE_LANGUAGE
import com.petroff.synchra.config.DEFAULT_TARGET_LANGUAGE
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.Mode
import com.petroff.synchra.config.sourceLanguagesFor
import com.petroff.synchra.config.targetLanguagesFor
import com.petroff.synchra.asr.ModelState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
    // Opens a help link; the browser by default.
    onOpenLink: ((String) -> Unit)? = null,
    // Applied at once, like the languages.
    crashReportsEnabled: Boolean = false,
    onCrashReportsChange: (Boolean) -> Unit = {},
    // Free / Paid: switched here, applied on Save like the keys.
    currentMode: Mode = Mode.PAID,
    onSaveMode: (Mode) -> Unit = {},
    // Free mode's speech model; its buttons act at once.
    modelState: ModelState = ModelState.NotDownloaded,
    onDownloadModel: () -> Unit = {},
    onCancelModel: () -> Unit = {},
    onDeleteModel: () -> Unit = {},
    // A recording is running: the mode and the model it may be using stay as they are.
    isRecording: Boolean = false,
    // Languages the on-device translator can translate into (Free mode's targets).
    onDeviceTargetCodes: Set<String> = emptySet(),
) {
    // Taken here, not inside the help dialog: a dialog is its own window with its own handler.
    val uriHandler = LocalUriHandler.current
    val openLink = onOpenLink ?: { url: String -> uriHandler.openUri(url) }

    // Which side's picker is open: true = source, false = target, null = none.
    var pickingSource by remember { mutableStateOf<Boolean?>(null) }

    var modeField by remember { mutableStateOf(currentMode) }

    pickingSource?.let { isSource ->
        LanguagePickerSheet(
            languages = if (isSource) sourceLanguagesFor(modeField) else targetLanguagesFor(modeField, onDeviceTargetCodes),
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
            text = {
                Text(
                    buildAnnotatedString {
                        append(content.text)
                        content.link?.let { (label, url) ->
                            append("\n\n")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "help",
                                    styles = TextLinkStyles(
                                        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
                                    ),
                                    linkInteractionListener = { openLink(url) },
                                ),
                            ) { append(label) }
                        }
                    },
                )
            },
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
                // The key field exists only in Paid mode.
                if (autoFocusApiKey && modeField == Mode.PAID) {
                    apiKeyFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }

            FieldRow(
                field = "Mode",
                helpText = "Free: speech is recognized and translated on this phone. Nothing is " +
                    "sent anywhere and no keys are needed, but it needs a one-time download of the " +
                    "speech model, the translation is simpler and there are no summaries. Paid: " +
                    "Soniox recognizes and translates (best quality, 60 languages), Claude writes " +
                    "summaries; both need your own API keys. Applied when you tap Save.",
                onShowHelp = { helpDialog = it },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("modeSwitch")) {
                        listOf(Mode.FREE to "Free", Mode.PAID to "Paid").forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = modeField == mode,
                                onClick = { modeField = mode },
                                enabled = !isRecording,
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            ) { Text(label) }
                        }
                    }
                    if (isRecording) StopRecordingFirst()
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

            if (modeField == Mode.FREE) {
                FieldRow(
                    field = "Speech model",
                    helpText = "Free mode recognizes speech on this phone with NVIDIA's Nemotron " +
                        "model (about 650 MB, 28 languages). It downloads once, in the background; " +
                        "an interrupted download continues where it stopped. Translation models " +
                        "(about 30 MB per language) download on the first Start.",
                    onShowHelp = { helpDialog = it },
                ) {
                    SpeechModelRow(modelState, isRecording, onDownloadModel, onCancelModel, onDeleteModel)
                }
            }

            if (modeField == Mode.PAID) FieldRow(
                field = "Soniox API key",
                helpText = "Your Soniox API key. Soniox recognizes your speech and translates it " +
                    "live (about \$0.12-0.23 per hour of audio). Required to start.",
                helpLink = "How to create a key and add funds" to SONIOX_SETUP_URL,
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

            if (modeField == Mode.PAID) FieldRow(
                field = "Anthropic API key",
                helpText = "Your Anthropic (Claude) API key, used for conversation summaries. " +
                    "Live transcription and translation work without it.",
                helpLink = "How to create a key and add funds" to CLAUDE_SETUP_URL,
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

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            FieldRow(
                field = "Send crash reports",
                helpText = "If Synchra crashes, an anonymous report goes to the developer through " +
                    "Firebase Crashlytics: device model, Android version and the app's last steps " +
                    "(e.g. \"session started\"). Conversations, contexts and API keys are never sent.",
                onShowHelp = { helpDialog = it },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send crash reports", modifier = Modifier.weight(1f))
                    Switch(
                        checked = crashReportsEnabled,
                        onCheckedChange = onCrashReportsChange,
                        modifier = Modifier.testTag("crashReportsSwitch"),
                    )
                }
            }

            Button(onClick = {
                onSaveMode(modeField)
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

@Composable
private fun StopRecordingFirst() {
    Text("Stop recording first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The speech model's status with the one button that fits it. */
@Composable
private fun SpeechModelRow(
    state: ModelState,
    isRecording: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val status = when (state) {
                ModelState.NotDownloaded -> "Not downloaded (≈650 MB)"
                is ModelState.Downloading -> "Downloading ${state.percent}%"
                ModelState.Unpacking -> "Unpacking… (a minute or two)"
                ModelState.Ready -> "Ready"
                ModelState.Failed -> "Download failed — tap Download to resume"
            }
            Text(status, modifier = Modifier.weight(1f).testTag("speechModelStatus"))
            when (state) {
                ModelState.NotDownloaded -> TextButton(onClick = onDownload) { Text("Download") }
                ModelState.Failed -> Row {
                    TextButton(onClick = onDownload) { Text("Download") }
                    // Throws away a partial download that keeps failing.
                    TextButton(onClick = onDelete, enabled = !isRecording) { Text("Delete") }
                }
                is ModelState.Downloading, ModelState.Unpacking -> TextButton(onClick = onCancel) { Text("Cancel") }
                ModelState.Ready -> TextButton(onClick = onDelete, enabled = !isRecording) { Text("Delete") }
            }
        }
        if (state is ModelState.Downloading) {
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (state == ModelState.Unpacking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state != ModelState.Ready) {
            Text("≈650 MB, Wi-Fi recommended", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class HelpDialogContent(
    val title: String,
    val text: String,
    // Label and address of a page with more help, opened in the browser.
    val link: Pair<String, String>? = null,
)

/** A field plus its help icon, laid out consistently across every Settings row. */
@Composable
private fun FieldRow(
    field: String,
    helpText: String,
    onShowHelp: (HelpDialogContent) -> Unit,
    helpLink: Pair<String, String>? = null,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            content()
        }
        IconButton(onClick = { onShowHelp(HelpDialogContent(field, helpText, helpLink)) }) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help: $field")
        }
    }
}

// Step-by-step instructions (ready ChatGPT questions) for an account, a key and its balance.
private const val SONIOX_SETUP_URL =
    "https://chatgpt.com/?q=Instructions%20for%20using%20Soniox%20(soniox.com)%3A%20how%20to%20create%20an%20API%20key%20and%20add%20funds%20to%20your%20account"

private const val CLAUDE_SETUP_URL =
    "https://chatgpt.com/?q=Instructions%20for%20using%20Claude%20(claude.com)%3A%20how%20to%20create%20an%20API%20key%20and%20add%20funds%20to%20your%20account"
