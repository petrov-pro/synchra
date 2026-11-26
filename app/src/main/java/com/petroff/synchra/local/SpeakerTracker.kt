package com.petroff.synchra.local

import kotlin.math.sqrt

/** A voice print of a piece of speech: close prints (cosine) mean the same speaker. */
interface SpeakerEmbedder {
    fun embed(samples: FloatArray): FloatArray
    fun release()
}

/**
 * Cuts the microphone's speech into pieces at short pauses, one speaker per piece as far as
 * turn-taking goes. Fed the same ~100 ms chunks the silence gate sees, with its speech decision.
 *
 * Tuned in tools/asr-bench/diarization_local.py: a 600 ms pause matches the recognizer's
 * endpoint (rule2 in RecognizerFactory), so a new piece starts where the previous phrase closed.
 */
class SpeechSegmenter(
    private val sampleRate: Int = 16_000,
    pauseMs: Long = 600,
    maxPieceMs: Long = 20_000,
) {
    sealed interface Event {
        /** Speech after a pause: a new piece, its speaker not known yet. */
        data class Started(val id: Int) : Event
        /** The piece's audio, from its first speech to its last. */
        data class Ended(val id: Int, val samples: FloatArray) : Event
    }

    private val pauseSamples = pauseMs * sampleRate / 1000
    // A voice print needs a few seconds, not minutes: memory stays bounded in a monologue.
    private val maxPieceSamples = (maxPieceMs * sampleRate / 1000).toInt()
    private var nextId = 0
    private var current: Int? = null
    private val pieceChunks = mutableListOf<FloatArray>()
    private var pieceSamples = 0
    // Chunks since the last speech, kept in case speech resumes before the pause is long enough.
    private val quietChunks = mutableListOf<FloatArray>()
    private var quietSamples = 0L

    fun process(chunk: FloatArray, isSpeech: Boolean): List<Event> {
        val events = mutableListOf<Event>()
        if (isSpeech) {
            if (current == null) {
                current = nextId++
                events += Event.Started(current!!)
            } else {
                quietChunks.forEach(::keep)
            }
            quietChunks.clear()
            quietSamples = 0
            keep(chunk)
        } else if (current != null) {
            quietChunks += chunk
            quietSamples += chunk.size
            if (quietSamples >= pauseSamples) events += endPiece()
        }
        return events
    }

    /** The piece in progress, if any, ends here (the session stops). */
    fun flush(): List<Event> = if (current != null) listOf(endPiece()) else emptyList()

    private fun keep(chunk: FloatArray) {
        if (pieceSamples >= maxPieceSamples) return
        pieceChunks += chunk
        pieceSamples += chunk.size
    }

    private fun endPiece(): Event.Ended {
        val samples = FloatArray(pieceSamples.coerceAtMost(maxPieceSamples))
        var at = 0
        for (c in pieceChunks) {
            val n = minOf(c.size, samples.size - at)
            c.copyInto(samples, at, 0, n)
            at += n
        }
        val ended = Event.Ended(current!!, samples)
        current = null
        pieceChunks.clear()
        pieceSamples = 0
        quietChunks.clear()
        quietSamples = 0
        return ended
    }
}

/**
 * Speakers as they are heard, numbered from 0 in order of appearance: a piece goes to the
 * closest speaker so far when its voice print is close enough, else it is a new speaker.
 * Pieces too short for a reliable print stay with the previous speaker.
 */
class SpeakerClusters(
    private val sampleRate: Int = 16_000,
    private val threshold: Float = 0.4f,
    private val minSpeechMs: Long = 750,
    private val maxSpeakers: Int = 8,
) {
    // Per speaker: sum of unit prints weighted by duration (its direction is the centroid).
    private val sums = mutableListOf<FloatArray>()
    private var previous: Int? = null

    /** [print] is computed only when needed: null means "too short, didn't embed". */
    fun assign(samples: FloatArray, print: (FloatArray) -> FloatArray): Int {
        val seconds = samples.size.toFloat() / sampleRate
        previous?.let { if (seconds * 1000 < minSpeechMs) return it }
        val unit = normalized(print(samples))
        val scores = sums.map { dot(unit, normalized(it)) }
        val best = scores.indices.maxByOrNull { scores[it] }
        val speaker = if (best != null && (scores[best] >= threshold || sums.size >= maxSpeakers)) {
            for (i in unit.indices) sums[best][i] += unit[i] * seconds
            best
        } else {
            sums += FloatArray(unit.size) { unit[it] * seconds }
            sums.size - 1
        }
        previous = speaker
        return speaker
    }

    private fun normalized(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x * x
        val n = sqrt(norm).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/** Where piece [id] of speech starts, among recognized or translated pieces of text. */
internal fun speakerMark(id: Int) = "$MARK_PREFIX$id"

private const val MARK_PREFIX = "\u0000speaker:"

internal fun isSpeakerMark(piece: String) = markId(piece) != null

private fun markId(piece: String): Int? =
    if (piece.startsWith(MARK_PREFIX)) piece.removePrefix(MARK_PREFIX).toIntOrNull() else null

/**
 * [pieces] with their speaker marks resolved, ready for joinPieces: once two or more speakers
 * were heard, each change of speaker starts a paragraph labeled "Speaker N:", from the start
 * (as Soniox labels in Paid mode). Marks whose speaker isn't known yet are skipped for now.
 */
internal fun labelSpeakers(pieces: List<String>, speakers: Map<Int, Int>): List<String> {
    val labeled = speakers.values.distinct().size >= 2
    val out = mutableListOf<String>()
    var current: Int? = null
    var label: String? = null
    var anyText = false
    for (piece in pieces) {
        val id = markId(piece)
        if (id != null) {
            val speaker = speakers[id] ?: continue
            if (speaker != current) {
                current = speaker
                if (labeled) {
                    if (anyText) out += PARAGRAPH_BREAK
                    label = "Speaker ${speaker + 1}:"
                }
            }
            continue
        }
        if (piece.isNotBlank() && piece != PARAGRAPH_BREAK) {
            label?.let { out += it }
            label = null
            anyText = true
        }
        out += piece
    }
    return out
}

private const val PARAGRAPH_BREAK = "\n\n"
