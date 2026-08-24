package com.petroff.transyncai.translate

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiTranslationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KimiTranslationClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KimiTranslationClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKeyProvider = { "sk-test-key" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the phrase and returns the translated text on success`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Привет мир"}}]}"""
            )
        )

        val result = client.translate(
            context = "hello there",
            currentPhrase = "world",
            sourceLang = "en",
            targetLang = "ru",
        )

        assertEquals("Привет мир", result.getOrThrow())

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"kimi-k2.5\""))
        assertTrue(body.contains("world"))
    }

    @Test
    fun `returns failure on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val result = client.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
    }

    @Test
    fun `returns failure without a network call when the api key is missing`() = runTest {
        val noKeyClient = KimiTranslationClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKeyProvider = { null },
        )

        val result = noKeyClient.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }
}
