package com.petroff.synchra.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicAudioSource : AudioSource {

    private val running = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onSamples: (FloatArray) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(CHUNK_SAMPLES * 2),
        )

        running.set(true)
        audioRecord.startRecording()

        recordingThread = thread(name = "MicAudioSource") {
            val shortBuffer = ShortArray(CHUNK_SAMPLES)
            while (running.get()) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768.0f }
                    onSamples(floatBuffer)
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    override fun stop() {
        running.set(false)
        recordingThread?.join(500)
        recordingThread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // ~100ms at 16kHz
    }
}
