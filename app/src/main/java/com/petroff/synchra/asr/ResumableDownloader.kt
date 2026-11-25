package com.petroff.synchra.asr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads into [part], continuing from the bytes already there when the server allows it. */
class ResumableDownloader(private val httpClient: OkHttpClient) {

    fun download(url: String, part: File, onProgress: (downloaded: Long, total: Long) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        httpClient.newCall(request).execute().use { response ->
            // Asked for bytes past the end: the file on disk is already whole.
            if (response.code == 416 && existing > 0) {
                val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (total == null || total == existing) {
                    onProgress(existing, existing)
                    return
                }
                throw IOException("Download failed: the partial file doesn't match the server's ($existing of $total bytes)")
            }
            val resumed = response.code == 206
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download failed: empty response")
            val start = if (resumed) existing else 0L
            val total = if (body.contentLength() >= 0) start + body.contentLength() else -1L
            var done = start
            FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
