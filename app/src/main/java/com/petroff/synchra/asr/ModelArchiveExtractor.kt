package com.petroff.synchra.asr

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Unpacks a .tar.bz2 model archive, keeping only the files the recognizer needs. */
object ModelArchiveExtractor {

    fun extract(archive: File, destDir: File, wanted: Set<String>) {
        destDir.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
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
    }
}
