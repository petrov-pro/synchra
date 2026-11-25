package com.petroff.synchra.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The language pair at the start of a top bar's actions, set apart from the buttons by a
 * divider. Short to fit the bar ("En-Ru"); screen readers get the full names.
 */
@Composable
fun LanguagePairLabel(shortLabel: String, sourceName: String, targetName: String) {
    val fullPair = "$sourceName → $targetName"
    Text(
        shortLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = fullPair }
            .testTag("languagePair"),
    )
    VerticalDivider(
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 4.dp)
            .testTag("languagePairDivider"),
    )
}
