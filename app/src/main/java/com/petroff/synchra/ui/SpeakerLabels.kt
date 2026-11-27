package com.petroff.synchra.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

// "Speaker N:" as SonioxTranscript puts it at the start of a paragraph, with the space after it.
private val SPEAKER_LABEL = Regex("""(?m)^(Speaker \d+:) ?""")

/**
 * [text] with each paragraph-leading "Speaker N:" label drawn in [labelStyle] on a line of its
 * own, so speaker changes stand out and the speech starts at the left edge.
 */
fun withSpeakerLabels(text: String, labelStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var from = 0
    SPEAKER_LABEL.findAll(text).forEach { match ->
        append(text.substring(from, match.range.first))
        withStyle(labelStyle) { append(match.groupValues[1]) }
        append('\n')
        from = match.range.last + 1
    }
    append(text.substring(from))
}
