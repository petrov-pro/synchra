package com.petroff.synchra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.petroff.synchra.session.ViewMode

/**
 * Source and translation panels, shown per [viewMode] (live on the main screen, saved on the
 * conversation screen). Each panel scrolls on its own and follows the latest text unless the
 * user scrolled back to read; stacked
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
    val scope = rememberCoroutineScope()
    // Follows the latest text until the user scrolls away; scrolling back to the end, or the
    // jump button, follows again.
    var follow by remember { mutableStateOf(true) }
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        scrollState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                follow = false
                userScrolled = true
            }
        }
    }
    LaunchedEffect(scrollState) {
        // Once the user's drag (and its fling) settles: back at the end means follow again.
        snapshotFlow { scrollState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && userScrolled) {
                userScrolled = false
                follow = !scrollState.canScrollForward
            }
        }
    }
    LaunchedEffect(text, follow) {
        if (follow) scrollState.animateScrollTo(scrollState.maxValue)
    }
    val farFromEnd = with(LocalDensity.current) { JUMP_BUTTON_DISTANCE.toPx() }
    val showJump by remember {
        derivedStateOf { !follow && scrollState.maxValue - scrollState.value > farFromEnd }
    }

    Column(modifier = modifier.copyOnLongPress(label, text)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val labelColor = MaterialTheme.colorScheme.primary
            val paragraphs = remember(text, labelColor) {
                val labelStyle = SpanStyle(fontWeight = FontWeight.Bold, color = labelColor)
                text.split(PARAGRAPH_BREAK).map { withSpeakerLabels(it, labelStyle) }
            }
            // One Text per paragraph, so the gap between them is a small fixed space rather
            // than a whole blank line of headline-sized text.
            Column(
                verticalArrangement = Arrangement.spacedBy(PARAGRAPH_GAP),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                paragraphs.forEach { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
            }
            JumpToLatestButton(
                visible = showJump,
                onClick = {
                    follow = true
                    scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .testTag("${label.lowercase()}JumpToLatest"),
            )
        }
    }
}

// Outside the panel's Column, whose scoped AnimatedVisibility would be picked otherwise.
@Composable
private fun JumpToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        // See-through, so the text under it stays readable.
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
        }
    }
}

// How far above the latest text the user must be for the jump button to show.
private val JUMP_BUTTON_DISTANCE = 120.dp

// How transcripts separate paragraphs (a pause or a change of speaker), and the space shown there.
private const val PARAGRAPH_BREAK = "\n\n"
private val PARAGRAPH_GAP = 8.dp
