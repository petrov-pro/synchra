package com.petroff.synchra.soniox

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides which microphone audio goes to Soniox. Soniox bills every second of audio it
 * receives, silence included, so after a phrase only a short tail is sent (enough for Soniox
 * to finish the phrase and its translation); then nothing until someone speaks again, with a
 * keepalive holding the stream open. A long silence asks to stop the session.
 *
 * Works on the microphone's chunks (~100 ms each); time is counted in audio, not wall clock.
 */
class SilenceGate(
    private val sampleRate: Int = 16_000,
    hangoverMs: Long = 1_500,
    preRollMs: Long = 500,
    keepaliveMs: Long = 10_000,
    autoStopMs: Long = 300_000,
) {
    data class Decision(
        /** Chunks to send now, oldest first (buffered lead-in + this chunk). */
        val send: List<FloatArray>,
        /** Send a keepalive: nothing has been sent for a while. */
        val keepalive: Boolean = false,
        /** Silence has lasted long enough to end the session. */
        val autoStop: Boolean = false,
    )

    private val hangoverSamples = hangoverMs * sampleRate / 1000
    private val preRollSamples = preRollMs * sampleRate / 1000
    private val keepaliveSamples = keepaliveMs * sampleRate / 1000
    private val autoStopSamples = autoStopMs * sampleRate / 1000

    // Background level of the room: drops at once to a quieter chunk, creeps up slowly, so
    // steady noise (fan, traffic) stops counting as speech while the pauses between words
    // keep it from climbing up to the speaker's own level.
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var samplesSinceSpeech = Long.MAX_VALUE / 2
    private var samplesSinceSent = 0L
    private var samplesSinceStart = 0L
    private var spokeYet = false
    private val preRoll = ArrayDeque<FloatArray>()
    private var preRollBuffered = 0L

    fun process(chunk: FloatArray): Decision {
        val level = rms(chunk)
        val isSpeech = level > max(MIN_SPEECH_RMS, noiseFloor * SPEECH_OVER_NOISE)
        noiseFloor = if (level < noiseFloor) max(level, INITIAL_NOISE_FLOOR / 3)
        else min(noiseFloor * NOISE_FLOOR_RISE, min(level, MAX_NOISE_FLOOR))
        samplesSinceStart += chunk.size

        if (isSpeech) {
            spokeYet = true
            samplesSinceSpeech = 0
            val send = preRoll.toList() + listOf(chunk)
            clearPreRoll()
            samplesSinceSent = 0
            return Decision(send)
        }

        samplesSinceSpeech += chunk.size
        val silenceSamples = if (spokeYet) samplesSinceSpeech else samplesSinceStart
        val autoStop = silenceSamples >= autoStopSamples

        if (spokeYet && samplesSinceSpeech <= hangoverSamples) {
            samplesSinceSent = 0
            return Decision(listOf(chunk), autoStop = autoStop)
        }

        bufferPreRoll(chunk)
        samplesSinceSent += chunk.size
        val keepalive = samplesSinceSent >= keepaliveSamples
        if (keepalive) samplesSinceSent = 0
        return Decision(emptyList(), keepalive = keepalive, autoStop = autoStop)
    }

    private fun bufferPreRoll(chunk: FloatArray) {
        preRoll.addLast(chunk)
        preRollBuffered += chunk.size
        while (preRollBuffered > preRollSamples) preRollBuffered -= preRoll.removeFirst().size
    }

    private fun clearPreRoll() {
        preRoll.clear()
        preRollBuffered = 0
    }

    private fun rms(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (s in chunk) sum += s * s
        return sqrt(sum / chunk.size).toFloat()
    }

    private companion object {
        // Low enough for a quiet or distant speaker (checked on the Svarah corpus, tools/asr-bench).
        const val MIN_SPEECH_RMS = 0.004f
        const val SPEECH_OVER_NOISE = 3f
        const val INITIAL_NOISE_FLOOR = 0.003f
        const val MAX_NOISE_FLOOR = 0.05f
        const val NOISE_FLOOR_RISE = 1.02f
    }
}
