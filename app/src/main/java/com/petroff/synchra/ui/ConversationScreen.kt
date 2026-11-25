package com.petroff.synchra.ui

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.petroff.synchra.config.shortPairLabel
import com.petroff.synchra.history.ConversationUiState
import com.petroff.synchra.history.ConversationView
import com.petroff.synchra.history.displayDateTime
import com.petroff.synchra.history.displayTitle
import com.petroff.synchra.session.ViewMode
import com.petroff.synchra.summary.SummarySections

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
                    // The languages the conversation was recorded in, as on the main screen.
                    record?.let {
                        LanguagePairLabel(
                            shortPairLabel(it.sourceLanguageName, it.targetLanguageName),
                            it.sourceLanguageName,
                            it.targetLanguageName,
                        )
                    }
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
                val title = record.displayTitle()
                // Blocks for summaries made since they existed; older ones are one plain text.
                val sections = SummarySections.parse(record.summary)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("summaryText")
                                .copyOnLongPress("Summary", sections?.toPlainText(title) ?: "$title\n\n${record.summary}")
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("summaryTitle"))
                    Text(
                        record.displayDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // Sets the title apart from the summary itself.
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp).testTag("summaryDivider"))
                    if (sections == null) {
                        Text(record.summary, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        // Empty blocks are left out, and so are the dividers around them.
                        sections.blocks().forEachIndexed { index, block ->
                            if (index > 0) SummaryBlockDivider()
                            SummaryBlock(block.heading, block.lines)
                        }
                    }
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

/** One block of a summary: its heading, then its text (one line per decision). */
@Composable
private fun SummaryBlock(heading: String, lines: List<String>) {
    Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    lines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SummaryBlockDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp).testTag("summaryBlockDivider"),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
