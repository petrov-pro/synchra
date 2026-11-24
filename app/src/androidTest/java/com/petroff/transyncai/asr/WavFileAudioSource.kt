package com.petroff.transyncai.asr

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds a bundled 16 kHz mono PCM16 WAV asset through the same path a real mic would use. */
class WavFileAudioSource(
    private val assetManager: AssetManager,
    private val assetPath: String,
) : AudioSource {

    private val running = AtomicBoolean(false)

    override fun start(onSamples: (FloatArray) -> Unit) {
        running.set(true)
        val samples = readPcm16Mono(assetManager.open(assetPath).use { it.readBytes() })
        var offset = 0
        while (running.get() && offset < samples.size) {
            val end = (offset + CHUNK_SAMPLES).coerceAtMost(samples.size)
            onSamples(samples.copyOfRange(offset, end))
            offset = end
        }
        running.set(false)
    }

    override fun stop() {
        running.set(false)
    }

    private fun readPcm16Mono(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12) // "RIFF" <size> "WAVE"
        while (buf.remaining() >= 8) {
            val id = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
            val size = buf.int
            when (id) {
                "fmt " -> {
                    val start = buf.position()
                    val format = buf.short.toInt()
                    val channels = buf.short.toInt()
                    val rate = buf.int
                    buf.position(start + 14)
                    val bits = buf.short.toInt()
                    require(format == 1 && channels == 1 && bits == 16 && rate == MicAudioSource.SAMPLE_RATE) {
                        "Test WAV must be ${MicAudioSource.SAMPLE_RATE} Hz mono PCM16"
                    }
                    buf.position(start + size)
                }
                "data" -> return FloatArray(size / 2) { buf.short / 32768f }
                else -> buf.position(buf.position() + size)
            }
        }
        error("no data chunk in $assetPath")
    }

    companion object {
        private const val CHUNK_SAMPLES = 1600
    }
}
