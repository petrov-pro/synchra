package com.petroff.synchra.asr

import java.io.File

/** The speech model's files in [modelDir], and which model they belong to. */
class ModelStore(private val modelDir: File) {

    private val marker get() = File(modelDir, MODEL_ID_FILE)

    /** The archive while it downloads; kept between attempts so a download resumes. */
    val archivePart: File get() = File(modelDir, ARCHIVE_PART)

    fun isReady(): Boolean =
        marker.isFile && marker.readText() == MODEL_ID && MODEL_FILES.all { File(modelDir, it).length() > 0 }

    fun paths(): ModelPaths = ModelPaths(
        encoder = File(modelDir, MODEL_FILES[0]).absolutePath,
        decoder = File(modelDir, MODEL_FILES[1]).absolutePath,
        joiner = File(modelDir, MODEL_FILES[2]).absolutePath,
        tokens = File(modelDir, MODEL_FILES[3]).absolutePath,
    )

    /**
     * Files of another (or an unknown) model are wiped first: a tokens.txt that doesn't match
     * the model makes sherpa-onnx abort the whole app. A partial archive of this model stays.
     */
    fun prepareForDownload() {
        if (!marker.isFile || marker.readText() != MODEL_ID) {
            modelDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        modelDir.mkdirs()
        marker.writeText(MODEL_ID)
    }

    fun delete() {
        modelDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val MODEL_ID_FILE = ".model-id"
        const val ARCHIVE_PART = "model.tar.bz2.part"
    }
}
