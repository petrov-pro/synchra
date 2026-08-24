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
        for (filename in MODEL_FILES) {
            val dest = File(modelDir, filename)
            if (!dest.exists() || dest.length() == 0L) {
                downloader.download(modelFileUrl(filename), dest)
            }
        }
        return ModelPaths(
            encoder = File(modelDir, MODEL_FILES[0]).absolutePath,
            decoder = File(modelDir, MODEL_FILES[1]).absolutePath,
            joiner = File(modelDir, MODEL_FILES[2]).absolutePath,
            tokens = File(modelDir, MODEL_FILES[3]).absolutePath,
        )
    }
}
