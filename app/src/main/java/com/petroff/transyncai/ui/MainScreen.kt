package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.config.LanguagePair
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: SessionUiState,
    languagePairs: List<LanguagePair>,
    selectedIndex: Int,
    onSelectLanguagePair: (Int) -> Unit,
    onToggleListening: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TransyncAI") },
                actions = {
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
            LanguagePairDropdown(languagePairs, selectedIndex, onSelectLanguagePair)

            // Disabled while listening: SessionViewModel's translate-gate (Task 8) checks
            // viewMode at segment-arrival time, not whether a key was present when the
            // session started — switching into Both/Translation-only mid-session with no
            // key set would surface a (harmless, no-network-call) error rather than being
            // caught proactively. Locking the switch while isListening closes that gap at
            // the UI layer instead of reworking already-reviewed ViewModel logic.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.viewMode == mode,
                        onClick = { onViewModeChange(mode) },
                        enabled = !uiState.isListening,
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

            if (uiState.viewMode != ViewMode.TRANSLATION_ONLY) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                Text(uiState.sourceText, style = MaterialTheme.typography.headlineSmall)
            }
            if (uiState.viewMode != ViewMode.TRANSCRIPTION_ONLY) {
                Text("Translation", style = MaterialTheme.typography.labelLarge)
                Text(uiState.translatedText, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

private fun ViewMode.label(): String = when (this) {
    ViewMode.BOTH -> "Both"
    ViewMode.TRANSCRIPTION_ONLY -> "Transcription"
    ViewMode.TRANSLATION_ONLY -> "Translation"
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
            modifier = Modifier.fillMaxWidth(),
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
