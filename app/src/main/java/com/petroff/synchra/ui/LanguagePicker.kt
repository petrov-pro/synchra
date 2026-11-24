package com.petroff.synchra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.SUPPORTED_LANGUAGES
import com.petroff.synchra.config.searchLanguages

/** "From [English] ⇄ [Russian]": tap a side to pick its language, the arrows to swap them. */
@Composable
fun LanguageBar(
    source: Language,
    target: Language,
    enabled: Boolean,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onPickSource,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag("sourceLanguage"),
        ) {
            Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onSwap, enabled = enabled) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap languages")
        }
        OutlinedButton(
            onClick = onPickTarget,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag("targetLanguage"),
        ) {
            Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Bottom sheet with a search field: recent languages first, then all of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    title: String,
    selected: Language,
    recent: List<Language>,
    onPick: (Language) -> Unit,
    onDismiss: () -> Unit,
    languages: List<Language> = SUPPORTED_LANGUAGES,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search languages") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("languageSearch"),
            )
            LazyColumn(modifier = Modifier.testTag("languageList")) {
                if (query.isBlank()) {
                    if (recent.isNotEmpty()) {
                        item { SectionLabel("Recent") }
                        items(recent, key = { "recent-${it.code}" }) { LanguageRow(it, it == selected, onPick) }
                    }
                    item { SectionLabel("All languages") }
                    items(languages, key = { "all-${it.code}" }) { LanguageRow(it, it == selected, onPick) }
                } else {
                    val found = searchLanguages(query, languages)
                    if (found.isEmpty()) item { SectionLabel("No language matches \"$query\"") }
                    items(found, key = { it.code }) { LanguageRow(it, it == selected, onPick) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(language: Language, isSelected: Boolean, onPick: (Language) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(language) }
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(language.name, style = MaterialTheme.typography.bodyLarge)
            if (language.nativeName != language.name) {
                Text(
                    language.nativeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSelected) Icon(Icons.Filled.Check, contentDescription = "Selected")
    }
}
