package com.petroff.synchra.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = tmp.root.resolve("model")

    private fun writeModel(dir: File, markerId: String) {
        dir.mkdirs()
        File(dir, ".model-id").writeText(markerId)
        MODEL_FILES.forEach { File(dir, it).writeText("x") }
    }

    @Test
    fun `not ready when the marker is for another model`() {
        val dir = dir()
        // The English model of the old app: same file names, other marker.
        writeModel(dir, "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main")
        val store = ModelStore(dir)

        assertFalse(store.isReady())

        store.prepareForDownload()
        assertEquals(setOf(".model-id"), dir.list()!!.toSet())
        assertEquals(MODEL_ID, File(dir, ".model-id").readText())
    }

    @Test
    fun `ready when the marker and all files are present`() {
        writeModel(dir(), MODEL_ID)
        assertTrue(ModelStore(dir()).isReady())
    }

    @Test
    fun `not ready when a file is empty`() {
        writeModel(dir(), MODEL_ID)
        File(dir(), "joiner.int8.onnx").writeText("")
        assertFalse(ModelStore(dir()).isReady())
    }

    @Test
    fun `prepare keeps a partial archive of the same model for resuming`() {
        val dir = dir()
        dir.mkdirs()
        File(dir, ".model-id").writeText(MODEL_ID)
        val store = ModelStore(dir)
        store.archivePart.writeText("half")

        store.prepareForDownload()

        assertEquals("half", store.archivePart.readText())
    }

    @Test
    fun `delete removes everything`() {
        writeModel(dir(), MODEL_ID)
        val store = ModelStore(dir())
        store.delete()
        assertFalse(store.isReady())
        assertTrue(dir().list().isNullOrEmpty())
    }
}
