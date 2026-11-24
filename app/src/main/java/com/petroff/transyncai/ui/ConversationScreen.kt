package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.history.ConversationUiState
import com.petroff.transyncai.history.ConversationView
import com.petroff.transyncai.history.displayTitle
import com.petroff.transyncai.session.ViewMode

/** One saved conversation: its Claude summary, or the original texts with the view switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onBack: () -> Unit,
    onSummaryClick: () -> Unit,
    onOriginalClick: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onDismissError: () -> Unit,
) {
    val record = state.record

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Summary") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text("OK") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record?.displayTitle().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Same Summary icon as on the main screen; Original switches back to the full texts.
                    if (state.view == ConversationView.SUMMARY) {
                        IconButton(onClick = onOriginalClick) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Original")
                        }
                    } else {
                        IconButton(onClick = onSummaryClick, enabled = !state.isSummarizing && record != null) {
                            Icon(Icons.Filled.Summarize, contentDescription = "Summary")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isSummarizing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Making summary…")
                }
            }
            if (record == null) return@Column

            if (state.view == ConversationView.SUMMARY && record.summary != null) {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(record.displayTitle(), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        record.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ViewMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.viewMode == mode,
                            onClick = { onViewModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ViewMode.entries.size),
                        ) {
                            Text(mode.label())
                        }
                    }
                }
                TranscriptPanels(
                    viewMode = state.viewMode,
                    sourceText = record.sourceText,
                    translatedText = record.translatedText,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}
