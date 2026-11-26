package com.petroff.synchra.asr

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArchiveExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun archive(vararg files: Pair<String, String>): File {
        val f = tmp.newFile("m.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(f.outputStream())).use { tar ->
            files.forEach { (name, text) ->
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return f
    }

    @Test
    fun `extracts only the wanted files, without the archive's folder`() {
        val src = archive("model-dir/tokens.txt" to "a 1", "model-dir/README.md" to "readme", "model-dir/test_wavs/x.wav" to "w")
        val dest = tmp.newFolder("out")

        ModelArchiveExtractor.extract(src, dest, setOf("tokens.txt"))

        assertEquals(setOf("tokens.txt"), dest.list()!!.toSet())
        assertEquals("a 1", File(dest, "tokens.txt").readText())
    }

    @Test
    fun `reports progress through the archive up to its full size`() {
        val src = archive("d/tokens.txt" to "x".repeat(200_000), "d/README.md" to "readme")
        val seen = mutableListOf<Long>()

        ModelArchiveExtractor.extract(src, tmp.newFolder("out2"), setOf("tokens.txt")) { read, total ->
            assertEquals(src.length(), total)
            seen += read
        }

        assertEquals(src.length(), seen.last())
        assertEquals(seen.sorted(), seen)
    }
}
