package com.petroff.transyncai.soniox

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
    fun `an error response becomes an error event`() {
        val event = transcript.apply(
            """{"tokens":[],"error_code":402,"error_message":"Organization balance exhausted."}"""
        )

        assertTrue(event is SonioxEvent.Error)
        assertTrue((event as SonioxEvent.Error).message.contains("balance exhausted"))
    }

    @Test
    fun `the finished response ends the stream`() {
        transcript.apply(msg(tok("Bye", true)))

        val event = transcript.apply("""{"tokens":[],"finished":true}""")

        assertEquals(SonioxEvent.Finished(source = "Bye", translation = ""), event)
    }
}
