package com.petroff.synchra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.petroff.synchra.session.ViewMode

/**
 * Source and translation panels, shown per [viewMode] (live on the main screen, saved on the
 * conversation screen). Each panel scrolls on its own and follows the latest text; stacked
 * top/bottom so full sentences stay readable on a phone, and a single visible panel fills
 * all the space.
 */
@Composable
fun TranscriptPanels(
    viewMode: ViewMode,
    sourceText: String,
    translatedText: String,
    modifier: Modifier = Modifier,
    // Shown in place of the translation when there is none to show and why.
    translationNote: String? = null,
) {
    Column(modifier = modifier) {
        if (viewMode != ViewMode.TRANSLATION_ONLY) {
            TextPanel(
                label = "Source",
                text = sourceText,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("sourcePanel"),
            )
        }
        // Only between the two panels — nothing to separate in a single-panel view mode.
        if (viewMode == ViewMode.BOTH) {
            HorizontalDivider(modifier = Modifier.testTag("sourceTranslationDivider"))
        }
        if (viewMode != ViewMode.TRANSCRIPTION_ONLY) {
            TextPanel(
                label = "Translation",
                text = translatedText,
                note = translationNote.takeIf { translatedText.isBlank() },
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("translationPanel"),
            )
        }
    }
}

@Composable
private fun TextPanel(label: String, text: String, modifier: Modifier = Modifier, note: String? = null) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(modifier = modifier.copyOnLongPress(label, text)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        )
    }
}
