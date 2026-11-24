package com.petroff.transyncai.translate

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeTranslationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ClaudeTranslationClient

    private fun messageResponse(text: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
               "content":[{"type":"text","text":"$text"}],
               "stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":40,"output_tokens":5}}"""
        )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ClaudeTranslationClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKeyProvider = { "sk-ant-test-key" },
            maxRetries = 0,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the phrase and returns the translated text on success`() = runTest {
        server.enqueue(messageResponse("Привет мир"))

        val result = client.translate(
            context = "hello there",
            currentPhrase = "world",
            sourceLang = "en",
            targetLang = "ru",
        )

        assertEquals("Привет мир", result.getOrThrow())

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("Bearer sk-ant-test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""))
        // Translation needs no reasoning: thinking off and the lowest effort keep it fast and cheap.
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"effort\":\"low\""))
        assertTrue(body.contains("CONTEXT: hello there"))
        assertTrue(body.contains("CURRENT: world"))
    }

    @Test
    fun `returns failure on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error"}"""))

        val result = client.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
    }

    @Test
    fun `returns failure without a network call when the api key is missing`() = runTest {
        val noKeyClient = ClaudeTranslationClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKeyProvider = { null },
            maxRetries = 0,
        )

        val result = noKeyClient.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `picks up a changed api key without being recreated`() = runTest {
        var key = "sk-ant-first"
        val switchingClient = ClaudeTranslationClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKeyProvider = { key },
            maxRetries = 0,
        )
        server.enqueue(messageResponse("один"))
        server.enqueue(messageResponse("два"))

        switchingClient.translate("", "one", "en", "ru")
        key = "sk-ant-second"
        switchingClient.translate("", "two", "en", "ru")

        assertEquals("Bearer sk-ant-first", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer sk-ant-second", server.takeRequest().getHeader("Authorization"))
    }
}
