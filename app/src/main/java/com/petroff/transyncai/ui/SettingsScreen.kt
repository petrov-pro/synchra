package com.petroff.transyncai.ui

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import com.petroff.transyncai.config.Engine
import com.petroff.transyncai.config.LanguagePair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentApiKey: String?,
    currentBufferSize: Int,
    currentChunkSizeWords: Int,
    currentGeneralContext: String,
    languagePairs: List<LanguagePair>,
    selectedLanguagePairIndex: Int,
    onSelectLanguagePair: (Int) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveBufferSize: (Int) -> Unit,
    onSaveChunkSizeWords: (Int) -> Unit,
    onSaveGeneralContext: (String) -> Unit,
    onBack: () -> Unit,
    // Set when a view-mode tab redirected here because no key was set yet (see MainScreen) —
    // most callers (opening Settings via the gear icon) leave this false.
    autoFocusApiKey: Boolean = false,
    currentEngine: Engine = Engine.LOCAL,
    currentSonioxApiKey: String? = null,
    onSaveEngine: (Engine) -> Unit = {},
    onSaveSonioxApiKey: (String) -> Unit = {},
) {
    var apiKeyField by remember { mutableStateOf(currentApiKey ?: "") }
    var engineField by remember { mutableStateOf(currentEngine) }
    var sonioxApiKeyField by remember { mutableStateOf(currentSonioxApiKey ?: "") }
    var bufferSizeField by remember { mutableStateOf(currentBufferSize.toFloat()) }
    var chunkSizeField by remember { mutableStateOf(currentChunkSizeWords.toFloat()) }
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
                helpText = "Choose the language you'll speak and the language it should be " +
                    "translated into.",
                onShowHelp = { helpDialog = it },
            ) {
                LanguagePairDropdown(languagePairs, selectedLanguagePairIndex, onSelectLanguagePair)
            }

            FieldRow(
                field = "Recognition & translation",
                helpText = "On device: speech is recognized on the phone (Nemotron) and translated " +
                    "by Claude. Soniox (cloud): audio is streamed to Soniox, which recognizes and " +
                    "translates it in one go (about \$0.12-0.23 per hour of audio).",
                onShowHelp = { helpDialog = it },
            ) {
                EngineSelector(engineField, onSelect = { engineField = it })
            }

            FieldRow(
                field = "Anthropic API key",
                helpText = "Your Anthropic (Claude) API key, used to call the translation service. " +
                    "Required for Both and Translation modes; Transcription-only works without it.",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = apiKeyField,
                    onValueChange = { apiKeyField = it },
                    label = { Text("Anthropic API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().focusRequester(apiKeyFocusRequester),
                )
            }

            FieldRow(
                field = "Soniox API key",
                helpText = "Your Soniox API key, used only when Recognition & translation is set " +
                    "to Soniox (cloud).",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = sonioxApiKeyField,
                    onValueChange = { sonioxApiKeyField = it },
                    label = { Text("Soniox API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FieldRow(
                field = "Context buffer size",
                helpText = "How many of your most recently finished phrases are kept and sent " +
                    "along with each new phrase, so the AI translates consistently with what " +
                    "was just said.",
                onShowHelp = { helpDialog = it },
            ) {
                Column {
                    Text("Context buffer size: ${bufferSizeField.toInt()} phrases")
                    Slider(
                        value = bufferSizeField,
                        onValueChange = { bufferSizeField = it },
                        valueRange = 2f..20f,
                        steps = 17,
                    )
                }
            }

            // How many new stable words must accumulate before a chunk is sent for
            // translation, independent of pauses — this is what makes translation
            // keep up with live speech instead of waiting for silence.
            FieldRow(
                field = "Translation chunk size",
                helpText = "How many new words must accumulate before they're sent for " +
                    "translation, without waiting for a pause. Lower values translate faster " +
                    "but in smaller, choppier pieces.",
                onShowHelp = { helpDialog = it },
            ) {
                Column {
                    Text("Translation chunk size: ${chunkSizeField.toInt()} words")
                    Slider(
                        value = chunkSizeField,
                        onValueChange = { chunkSizeField = it },
                        valueRange = 3f..15f,
                        steps = 11,
                    )
                }
            }

            // Set apart from the connection/session fields above — general context is a
            // different kind of setting (standing background for the AI, not a connection or
            // session parameter) — with extra breathing room and a divider ahead of it.
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // Standing context sent with every translation unless a per-conversation context
            // (set from the Main screen's Context button) is active — see SessionViewModel.
            FieldRow(
                field = "General context",
                helpText = "Background sent with every translation to help the AI understand " +
                    "your topic and terminology, unless you set a more specific context for " +
                    "the current conversation using the Context button on the main screen.",
                onShowHelp = { helpDialog = it },
            ) {
                TextField(
                    value = generalContextField,
                    onValueChange = { generalContextField = it },
                    label = { Text("General context") },
                    placeholder = { Text("e.g. We're discussing a technical support call about billing") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(onClick = {
                onSaveApiKey(apiKeyField)
                onSaveEngine(engineField)
                onSaveSonioxApiKey(sonioxApiKeyField)
                onSaveBufferSize(bufferSizeField.toInt())
                onSaveChunkSizeWords(chunkSizeField.toInt())
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineSelector(selected: Engine, onSelect: (Engine) -> Unit) {
    val labels = mapOf(Engine.LOCAL to "On device", Engine.SONIOX to "Soniox (cloud)")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        Engine.entries.forEachIndexed { index, engine ->
            SegmentedButton(
                selected = engine == selected,
                onClick = { onSelect(engine) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Engine.entries.size),
            ) {
                Text(labels.getValue(engine))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePairDropdown(
    languagePairs: List<LanguagePair>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languagePairs.getOrNull(selectedIndex) ?: languagePairs.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            readOnly = true,
            value = "${selected.sourceName} → ${selected.targetName}",
            onValueChange = {},
            label = { Text("Languages") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languagePairs.forEachIndexed { index, pair ->
                DropdownMenuItem(
                    text = { Text("${pair.sourceName} → ${pair.targetName}") },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
