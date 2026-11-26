package com.petroff.synchra.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

// "Speaker N:" as SonioxTranscript puts it at the start of a paragraph.
private val SPEAKER_LABEL = Regex("""(?m)^Speaker \d+:""")

/** [text] with each paragraph-leading "Speaker N:" label drawn in [labelStyle], so speaker changes stand out. */
fun withSpeakerLabels(text: String, labelStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    SPEAKER_LABEL.findAll(text).forEach { addStyle(labelStyle, it.range.first, it.range.last + 1) }
}
