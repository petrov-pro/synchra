package com.petroff.transyncai.asr

import java.io.File

interface FileDownloader {
    fun download(url: String, dest: File)
}

class ModelFileProvider(
    private val downloader: FileDownloader,
    private val modelDir: File,
) {
    fun ensureModelFiles(): ModelPaths {
        modelDir.mkdirs()
        val marker = File(modelDir, MODEL_ID_FILE)
        if (!marker.exists() || marker.readText() != MODEL_ID) {
            // Another (or an unknown) model's files: wipe everything, including same-named
            // files like tokens.txt — a mismatched vocabulary makes sherpa-onnx abort the app.
            modelDir.listFiles()?.forEach { it.deleteRecursively() }
            // Written up front: each file below lands atomically, so files completed before an
            // interruption belong to this model and are kept on the next attempt.
            marker.writeText(MODEL_ID)
        } else {
            // Stale .part files from an interrupted download.
            modelDir.listFiles()?.filter { it.name !in MODEL_FILES && it.name != MODEL_ID_FILE }
                ?.forEach { it.deleteRecursively() }
        }
        for (filename in MODEL_FILES) {
            val dest = File(modelDir, filename)
            if (!dest.exists() || dest.length() == 0L) {
                // Download under a temporary name: an interrupted download must never
                // leave a truncated file that the exists() check above would accept.
                val part = File(modelDir, "$filename.part")
                try {
                    downloader.download(modelFileUrl(filename), part)
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
                check(part.renameTo(dest)) { "Failed to move $part to $dest" }
            }
        }
        return ModelPaths(
            encoder = File(modelDir, MODEL_FILES[0]).absolutePath,
            decoder = File(modelDir, MODEL_FILES[1]).absolutePath,
            joiner = File(modelDir, MODEL_FILES[2]).absolutePath,
            tokens = File(modelDir, MODEL_FILES[3]).absolutePath,
        )
    }

    private companion object {
        const val MODEL_ID_FILE = ".model-id"
    }
}
