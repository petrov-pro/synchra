package com.petroff.synchra.asr

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Unpacks a .tar.bz2 model archive, keeping only the files the recognizer needs. */
object ModelArchiveExtractor {

    /** [onProgress]: bytes of the archive read so far, and its size. */
    fun extract(
        archive: File,
        destDir: File,
        wanted: Set<String>,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        destDir.mkdirs()
        val total = archive.length()
        // Progress is counted on the compressed side: the archive's size is known up front,
        // the unpacked size isn't.
        val counting = CountingInputStream(archive.inputStream()) { read -> onProgress(read, total) }
        TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                // Under a temporary name: an interrupted extraction never leaves a truncated
                // file that looks complete.
                val part = File(destDir, "$name.part")
                part.outputStream().use { tar.copyTo(it) }
                val dest = File(destDir, name)
                dest.delete()
                check(part.renameTo(dest)) { "Failed to move $part to $dest" }
            }
        }
        onProgress(total, total)
    }
}

private class CountingInputStream(
    input: InputStream,
    private val onRead: (Long) -> Unit,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(n: Long) {
        count += n
        onRead(count)
    }
}
