package com.petroff.transyncai.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.WaveReader
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds a bundled WAV asset through the same acceptWaveform path a real mic would use. */
class WavFileAudioSource(
    private val assetManager: AssetManager,
    private val assetPath: String,
) : AudioSource {

    private val running = AtomicBoolean(false)

    override fun start(onSamples: (FloatArray) -> Unit) {
        running.set(true)
        val wave = WaveReader.readWave(assetManager, assetPath)
        require(wave.sampleRate == MicAudioSource.SAMPLE_RATE) {
            "Test WAV must be ${MicAudioSource.SAMPLE_RATE}Hz to match the model's expected input, got ${wave.sampleRate}Hz"
        }
        var offset = 0
        while (running.get() && offset < wave.samples.size) {
            val end = (offset + CHUNK_SAMPLES).coerceAtMost(wave.samples.size)
            onSamples(wave.samples.copyOfRange(offset, end))
            offset = end
        }
        running.set(false)
    }

    override fun stop() {
        running.set(false)
    }

    companion object {
        private const val CHUNK_SAMPLES = 1600
    }
}
