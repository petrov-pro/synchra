package com.petroff.transyncai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.displayTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    records: List<ConversationRecord>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRecordClick: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this conversation?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecord(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (records.isEmpty()) {
                Text(
                    "No conversations yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(records, key = { it.id }) { record ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onRecordClick(record.id) },
                            ) {
                                Text(record.displayTitle(), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    record.translatedText.ifBlank { record.sourceText }.take(60),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { pendingDeleteId = record.id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                            }
                        }
                    }
                }
            }
        }
    }
}
