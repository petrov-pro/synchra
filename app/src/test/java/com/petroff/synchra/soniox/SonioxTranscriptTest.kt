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
}

