package com.petroff.synchra.asr

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val downloader = ResumableDownloader(OkHttpClient())

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    @Test
    fun `resumes from the bytes already on disk`() {
        val part = tmp.newFile("m.part").apply { writeText("hello ") }
        server.enqueue(MockResponse().setResponseCode(206).setBody("world").setHeader("Content-Range", "bytes 6-10/11"))
        var last: Pair<Long, Long>? = null

        downloader.download(server.url("/m").toString(), part) { done, total -> last = done to total }

        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        assertEquals("hello world", part.readText())
        assertEquals(11L to 11L, last)
    }

    @Test
    fun `starts over when the server ignores the range`() {
        val part = tmp.newFile("m.part").apply { writeText("stale ") }
        server.enqueue(MockResponse().setResponseCode(200).setBody("full body"))

        downloader.download(server.url("/m").toString(), part) { _, _ -> }

        assertEquals("full body", part.readText())
    }

    @Test
    fun `a fresh download sends no range`() {
        val part = tmp.root.resolve("new.part")
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))

        downloader.download(server.url("/m").toString(), part) { _, _ -> }

        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("abc", part.readText())
    }

    @Test
    fun `fails on an http error and keeps what was downloaded`() {
        val part = tmp.newFile("m.part").apply { writeText("hello ") }
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            downloader.download(server.url("/m").toString(), part) { _, _ -> }
            fail("expected an exception")
        } catch (e: Exception) {
            assertEquals("hello ", part.readText())
        }
    }

    @Test
    fun `a range past the end means the file is already complete`() {
        val part = tmp.newFile("m.part").apply { writeText("hello world") }
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */11"))
        var last: Pair<Long, Long>? = null

        downloader.download(server.url("/m").toString(), part) { done, total -> last = done to total }

        assertEquals("hello world", part.readText())
        assertEquals(11L to 11L, last)
    }
}
