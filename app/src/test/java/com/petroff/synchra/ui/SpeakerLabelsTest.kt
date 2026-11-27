package com.petroff.synchra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerLabelsTest {
    private val style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)

    private fun labels(text: String) =
        withSpeakerLabels(text, style).let { styled -> styled.spanStyles.map { styled.text.substring(it.start, it.end) } }

    @Test
    fun stylesLabelsAtParagraphStarts() {
        val text = "Speaker 1: Hello.\n\nSpeaker 2: Hi there.\n\nmore from two\n\nSpeaker 12: Bye."
        assertEquals(listOf("Speaker 1:", "Speaker 2:", "Speaker 12:"), labels(text))
    }

    @Test
    fun putsEachLabelOnItsOwnLine() {
        val text = "Speaker 1: Hello.\n\nmore from one\n\nSpeaker 2: Hi there."
        assertEquals(
            "Speaker 1:\nHello.\n\nmore from one\n\nSpeaker 2:\nHi there.",
            withSpeakerLabels(text, style).text,
        )
    }

    @Test
    fun leavesLabelLikeWordsInsideTextAlone() {
        val text = "He said Speaker 1: was loud."
        assertEquals(emptyList<String>(), labels(text))
        assertEquals(text, withSpeakerLabels(text, style).text)
    }

    @Test
    fun plainTextHasNoStyles() {
        assertEquals(emptyList<String>(), labels("Just one voice talking."))
    }
}
