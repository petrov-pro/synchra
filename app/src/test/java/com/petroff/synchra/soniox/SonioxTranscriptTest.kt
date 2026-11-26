package com.petroff.synchra.soniox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonioxTranscriptTest {

    private val transcript = SonioxTranscript()

    private fun tok(text: String, final: Boolean, status: String = "original") =
        """{"text":"$text","is_final":$final,"translation_status":"$status"}"""

    private fun msg(vararg tokens: String) = """{"tokens":[${tokens.joinToString(",")}]}"""

    @Test
    fun `final tokens accumulate while non-final ones are replaced by the next message`() {
        transcript.apply(msg(tok("Hello", true), tok(" wor", false)))
        val event = transcript.apply(msg(tok(" world", true), tok(" how", false)))

        assertEquals(SonioxEvent.Update(source = "Hello world how", translation = ""), event)
    }

    @Test
    fun `translation tokens go to the translation and the end marker is dropped`() {
        transcript.apply(msg(tok("Good morning", true), tok("<end>", true)))
        val event = transcript.apply(
            msg(tok("Доброе", true, "translation"), tok(" утро", false, "translation"))
        )

        assertEquals(SonioxEvent.Update(source = "Good morning", translation = "Доброе утро"), event)
    }

    @Test
    fun `the finished response ends the stream`() {
        transcript.apply(msg(tok("Bye", true)))

        val event = transcript.apply("""{"tokens":[],"finished":true}""")

        assertEquals(SonioxEvent.Finished(source = "Bye", translation = ""), event)
    }

    @Test
    fun `empty balance becomes the top-up message`() {
        val event = transcript.apply("""{"tokens":[],"error_code":402,"error_message":"Organization balance exhausted."}""")

        assertEquals(SonioxEvent.Error("Your Soniox balance is empty. Top it up at console.soniox.com."), event)
    }

    @Test
    fun `rejected key becomes the settings message`() {
        val event = transcript.apply("""{"tokens":[],"error_code":401,"error_message":"Incorrect API key provided."}""")

        assertEquals(SonioxEvent.Error("Your Soniox API key is invalid. Check it in Settings."), event)
    }

    @Test
    fun `other errors keep the API text`() {
        val event = transcript.apply("""{"tokens":[],"error_code":500,"error_message":"Internal error."}""")

        assertTrue((event as SonioxEvent.Error).message.contains("Soniox 500"))
    }

    @Test
    fun `speech after a pause starts a new paragraph on both sides, with one empty line`() {
        transcript.apply(msg(tok("Hello", true), tok("Привет", true, "translation")))
        transcript.paragraphBreak()
        // A second pause before anyone speaks again still makes one empty line only.
        transcript.paragraphBreak()

        val event = transcript.apply(msg(tok(" Bye", true), tok(" Пока", true, "translation")))

        assertEquals(SonioxEvent.Update(source = "Hello\n\nBye", translation = "Привет\n\nПока"), event)
    }

    @Test
    fun `the paragraph shows already with the non-final words and waits past a lone space`() {
        transcript.apply(msg(tok("Hello", true)))
        transcript.paragraphBreak()

        val preview = transcript.apply(msg(tok(" Bye", false)))
        assertEquals(SonioxEvent.Update(source = "Hello\n\nBye", translation = ""), preview)

        val event = transcript.apply(msg(tok(" ", true), tok(" Bye", true)))
        assertEquals(SonioxEvent.Update(source = "Hello\n\nBye", translation = ""), event)
    }

    @Test
    fun `a pause before anything was said adds no empty line`() {
        transcript.paragraphBreak()

        val event = transcript.apply(msg(tok("Hello", true)))

        assertEquals(SonioxEvent.Update(source = "Hello", translation = ""), event)
    }

    private fun said(text: String, speaker: String, status: String = "original", final: Boolean = true) =
        """{"text":"$text","is_final":$final,"translation_status":"$status","speaker":"$speaker"}"""

    @Test
    fun `with two speakers each change of speaker is a labeled paragraph on both sides`() {
        transcript.apply(msg(said("Hi", "1"), said(" there.", "1"), said("Привет.", "1", "translation")))
        val event = transcript.apply(
            msg(said(" Hello.", "2"), said(" Bye.", "1"), said(" Здравствуй.", "2", "translation"))
        )

        assertEquals(
            SonioxEvent.Update(
                source = "Speaker 1: Hi there.\n\nSpeaker 2: Hello.\n\nSpeaker 1: Bye.",
                translation = "Speaker 1: Привет.\n\nSpeaker 2: Здравствуй.",
            ),
            event,
        )
    }

    @Test
    fun `one speaker gets no labels, and a pause keeps the speaker without repeating the label`() {
        transcript.apply(msg(said("Hi.", "1")))
        transcript.paragraphBreak()
        val alone = transcript.apply(msg(said(" Again.", "1")))
        assertEquals(SonioxEvent.Update(source = "Hi.\n\nAgain.", translation = ""), alone)

        val event = transcript.apply(msg(said(" Me", "2", final = false)))
        assertEquals(
            SonioxEvent.Update(source = "Speaker 1: Hi.\n\nAgain.\n\nSpeaker 2: Me", translation = ""),
            event,
        )
    }
}
