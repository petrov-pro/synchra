package com.petroff.synchra.summary

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeSummarizerTest {

    private lateinit var server: MockWebServer

    private fun claudeAnswer(text: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
               "content":[{"type":"text","text":${JsonPrimitive(text)}}],
               "stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":400,"output_tokens":60}}"""
        )

    private fun apiError(code: Int, type: String, message: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"$message"}}""")

    private fun summarizer(key: String? = "sk-ant-test") = ClaudeSummarizer(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyProvider = { key },
    )

    private fun kindOf(result: Result<ConversationSummary>) =
        (result.exceptionOrNull() as SummaryException).kind

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns title and summary parsed from Claude's JSON`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"Встреча","summary":"Обсудили сроки."}"""))

        val result = summarizer().summarize("We agreed on the deadline.", "Мы согласовали срок.", "Russian")

        assertEquals(ConversationSummary("Встреча", "Обсудили сроки."), result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""))
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("Russian"))
        assertTrue(body.contains("We agreed on the deadline."))
        assertTrue(body.contains("Мы согласовали срок."))
    }

    @Test
    fun `transcription-only conversation sends only the source text`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize("Only the original.", "", "Russian")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Only the original."))
        assertFalse(body.contains("TRANSLATION:"))
    }

    @Test
    fun `long conversation is sent whole`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))
        val long = "word ".repeat(12_000) + "THE-VERY-END-MARKER"

        summarizer().summarize(long, "", "Russian")

        assertTrue(server.takeRequest().body.readUtf8().contains("THE-VERY-END-MARKER"))
    }

    @Test
    fun `malformed answer is a failure`() = runTest {
        server.enqueue(claudeAnswer("not json"))
        server.enqueue(claudeAnswer("""{"title":"","summary":"x"}"""))

        val notJson = summarizer().summarize("a", "b", "Russian")
        val blankTitle = summarizer().summarize("a", "b", "Russian")

        assertTrue(notJson.isFailure)
        assertEquals(SummaryException.Kind.TEMPORARY, kindOf(notJson))
        assertTrue(blankTitle.isFailure)
    }

    @Test
    fun `server error is a temporary failure without SDK retries`() = runTest {
        server.enqueue(apiError(500, "api_error", "Internal server error"))

        val result = summarizer().summarize("a", "b", "Russian")

        assertEquals(SummaryException.Kind.TEMPORARY, kindOf(result))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty balance is OUT_OF_CREDIT`() = runTest {
        server.enqueue(
            apiError(
                400,
                "invalid_request_error",
                "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.",
            )
        )

        assertEquals(SummaryException.Kind.OUT_OF_CREDIT, kindOf(summarizer().summarize("a", "b", "Russian")))
    }

    @Test
    fun `rejected key is INVALID_KEY`() = runTest {
        server.enqueue(apiError(401, "authentication_error", "invalid x-api-key"))

        assertEquals(SummaryException.Kind.INVALID_KEY, kindOf(summarizer().summarize("a", "b", "Russian")))
    }

    @Test
    fun `missing api key fails without a request`() = runTest {
        val result = summarizer(key = null).summarize("a", "b", "Russian")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the recorded context goes to Claude as background`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize("Hi", "Привет", "Russian", conversationContext = "Follow-up with patient Ravi")

        assertTrue(server.takeRequest().body.readUtf8().contains("Follow-up with patient Ravi"))
    }

    @Test
    fun `no context means no context section`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize("Hi", "Привет", "Russian")

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("ABOUT THE USER (who recorded"))
        assertFalse(body.contains("ABOUT THIS CONVERSATION (from the user)"))
    }

    @Test
    fun `Claude is told the context is background, not part of the conversation`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize("Hi", "Привет", "Russian", conversationContext = "drugs: metoprolol")

        assertTrue(server.takeRequest().body.readUtf8().contains("never summarize them or present them as something said"))
    }

    @Test
    fun `Claude is told the recorder may not be a speaker, so no one is credited without the transcript saying so`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize("The engineers said it was done", "", "Russian", userContext = "My name is Andrey")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("may not have said anything"))
        assertTrue(body.contains("Never attribute a statement, task or decision to the user"))
        assertTrue(body.contains("ABOUT THE USER (who recorded; may not speak in the conversation):\\nMy name is Andrey"))
    }

    @Test
    fun `the user's background and the conversation's context are separate sections`() = runTest {
        server.enqueue(claudeAnswer("""{"title":"T","summary":"S"}"""))

        summarizer().summarize(
            "Let's plan the sprint", "", "Russian",
            userContext = "My name is Andrey",
            conversationContext = "Sprint planning with John and Maria",
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("ABOUT THE USER (who recorded; may not speak in the conversation):\\nMy name is Andrey"))
        assertTrue(body.contains("ABOUT THIS CONVERSATION (from the user):\\nSprint planning with John and Maria"))
        assertTrue(body.contains("do not guess which speaker is which participant"))
    }
}
