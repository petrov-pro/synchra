package com.petroff.synchra.local

import com.petroff.synchra.local.SpeechSegmenter.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTrackerTest {

    private fun chunk(value: Float = 0.1f) = FloatArray(1_600) { value } // 100 ms

    private fun SpeechSegmenter.feed(speech: Int, quiet: Int = 0): List<Event> =
        (0 until speech).flatMap { process(chunk(), isSpeech = true) } +
            (0 until quiet).flatMap { process(chunk(0f), isSpeech = false) }

    @Test
    fun `a short pause keeps one piece, a 600 ms pause ends it`() {
        val s = SpeechSegmenter()
        assertEquals(listOf(Event.Started(0)), s.feed(speech = 5, quiet = 3))
        val ended = s.feed(speech = 5, quiet = 6)
        assertEquals(1, ended.size)
        val piece = ended.single() as Event.Ended
        assertEquals(0, piece.id)
        // Both runs of speech and the short pause between them; not the closing pause.
        assertEquals(13 * 1_600, piece.samples.size)
        assertEquals(listOf(Event.Started(1)), s.feed(speech = 1))
    }

    @Test
    fun `flush ends the piece in progress, and nothing when quiet`() {
        val s = SpeechSegmenter()
        assertTrue(s.flush().isEmpty())
        s.feed(speech = 4)
        val ended = s.flush().single() as Event.Ended
        assertEquals(4 * 1_600, ended.samples.size)
        assertTrue(s.flush().isEmpty())
    }

    @Test
    fun `a long monologue keeps at most the first 20 seconds`() {
        val s = SpeechSegmenter()
        s.feed(speech = 300)
        assertEquals(20 * 16_000, (s.flush().single() as Event.Ended).samples.size)
    }

    // Prints: the first sample says whose voice it is.
    private val prints: (FloatArray) -> FloatArray = { samples ->
        if (samples[0] < 0.15f) floatArrayOf(1f, 0.1f) else floatArrayOf(0.1f, 1f)
    }

    @Test
    fun `speakers are numbered in order of appearance and found again`() {
        val c = SpeakerClusters()
        val a = FloatArray(16_000) { 0.1f }
        val b = FloatArray(16_000) { 0.2f }
        assertEquals(listOf(0, 1, 0, 1), listOf(a, b, a, b).map { c.assign(it, prints) })
    }

    @Test
    fun `a piece too short for a print stays with the previous speaker`() {
        val c = SpeakerClusters()
        var embedded = 0
        val counting: (FloatArray) -> FloatArray = { embedded++; prints(it) }
        assertEquals(0, c.assign(FloatArray(16_000) { 0.1f }, counting))
        assertEquals(0, c.assign(FloatArray(8_000) { 0.2f }, counting))
        assertEquals(1, embedded)
    }

    @Test
    fun `a single speaker gets no labels`() {
        val pieces = listOf(speakerMark(0), "hello", speakerMark(1), "again")
        assertEquals(listOf("hello", "again"), labelSpeakers(pieces, mapOf(0 to 0, 1 to 0)))
    }

    @Test
    fun `each change of speaker starts a labeled paragraph, from the start`() {
        val pieces = listOf(speakerMark(0), "hello", speakerMark(1), "hi", speakerMark(2), "and", "more")
        assertEquals(
            "Speaker 1: hello\n\nSpeaker 2: hi\n\nSpeaker 1: and more",
            joinPieces(labelSpeakers(pieces, mapOf(0 to 0, 1 to 1, 2 to 0))),
        )
    }

    @Test
    fun `a piece whose speaker isn't known yet stays with the previous one`() {
        val pieces = listOf(speakerMark(0), "hello", speakerMark(1), "hi", speakerMark(2), "there")
        assertEquals(
            "Speaker 1: hello\n\nSpeaker 2: hi there",
            joinPieces(labelSpeakers(pieces, mapOf(0 to 0, 1 to 1))),
        )
    }
}
