package com.petroff.transyncai.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Writes part of the file, then fails — like a dropped connection mid-download. */
class InterruptedFileDownloader : FileDownloader {
    override fun download(url: String, dest: File) {
        dest.writeText("partial")
        throw java.io.IOException("connection reset")
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
        File(tmp.root, ".model-id").writeText(MODEL_ID)
        File(tmp.root, "tokens.txt").writeText("already-here")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.none { it.endsWith("tokens.txt") })
    }

    @Test
    fun `re-downloads a file that exists but is empty`() {
        val downloader = FakeFileDownloader()
        File(tmp.root, ".model-id").writeText(MODEL_ID)
        File(tmp.root, "tokens.txt").writeText("")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.any { it.endsWith("tokens.txt") })
    }

    @Test
    fun `interrupted download leaves no file that would later be treated as complete`() {
        val result = runCatching {
            ModelFileProvider(InterruptedFileDownloader(), tmp.root).ensureModelFiles()
        }

        assertTrue(result.isFailure)
        assertTrue(MODEL_FILES.none { File(tmp.root, it).exists() })
    }

    @Test
    fun `deletes leftover files from a previous model`() {
        val stale = File(tmp.root, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
        stale.writeText("old model")

        ModelFileProvider(FakeFileDownloader(), tmp.root).ensureModelFiles()

        assertFalse(stale.exists())
    }

    @Test
    fun `re-downloads same-named files left by a previous model`() {
        // Both the old zipformer and Nemotron ship a tokens.txt: keeping the old one made
        // sherpa-onnx abort ("<blk> is not the last token!") and kill the app.
        File(tmp.root, "tokens.txt").writeText("zipformer vocabulary")
        val downloader = FakeFileDownloader()

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.any { it.endsWith("tokens.txt") })
        assertEquals("fake-content-for-tokens.txt.part", File(tmp.root, "tokens.txt").readText())
    }

    @Test
    fun `downloads nothing when the current model is already complete`() {
        ModelFileProvider(FakeFileDownloader(), tmp.root).ensureModelFiles()
        val second = FakeFileDownloader()

        ModelFileProvider(second, tmp.root).ensureModelFiles()

        assertTrue(second.requested.isEmpty())
    }
}
