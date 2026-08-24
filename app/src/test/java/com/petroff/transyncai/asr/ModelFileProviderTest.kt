package com.petroff.transyncai.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeFileDownloader : FileDownloader {
    val requested = mutableListOf<String>()
    override fun download(url: String, dest: File) {
        requested.add(url)
        dest.writeText("fake-content-for-${dest.name}")
    }
}

class ModelFileProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `downloads every model file into modelDir when none exist`() {
        val downloader = FakeFileDownloader()
        val provider = ModelFileProvider(downloader, tmp.root)

        val paths = provider.ensureModelFiles()

        assertEquals(4, downloader.requested.size)
        assertTrue(File(paths.encoder).exists())
        assertTrue(File(paths.decoder).exists())
        assertTrue(File(paths.joiner).exists())
        assertTrue(File(paths.tokens).exists())
    }

    @Test
    fun `skips files that already exist and are non-empty`() {
        val downloader = FakeFileDownloader()
        File(tmp.root, "tokens.txt").writeText("already-here")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.none { it.endsWith("tokens.txt") })
    }

    @Test
    fun `re-downloads a file that exists but is empty`() {
        val downloader = FakeFileDownloader()
        File(tmp.root, "tokens.txt").writeText("")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.any { it.endsWith("tokens.txt") })
    }
}
