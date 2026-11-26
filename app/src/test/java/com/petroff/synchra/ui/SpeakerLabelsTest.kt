package com.petroff.synchra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerLabelsTest {
    private val style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)

    private fun labels(text: String) =
        withSpeakerLabels(text, style).spanStyles.map { text.substring(it.start, it.end) }

    @Test
    fun stylesLabelsAtParagraphStarts() {
        val text = "Speaker 1: Hello.\n\nSpeaker 2: Hi there.\n\nmore from two\n\nSpeaker 12: Bye."
        assertEquals(listOf("Speaker 1:", "Speaker 2:", "Speaker 12:"), labels(text))
        assertEquals(text, withSpeakerLabels(text, style).text)
    }

    @Test
    fun leavesLabelLikeWordsInsideTextAlone() {
        assertEquals(emptyList<String>(), labels("He said Speaker 1: was loud."))
    }

    @Test
    fun plainTextHasNoStyles() {
        assertEquals(emptyList<String>(), labels("Just one voice talking."))
    }
}
