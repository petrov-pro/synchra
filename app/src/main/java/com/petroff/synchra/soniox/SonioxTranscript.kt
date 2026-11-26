package com.petroff.synchra.soniox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface SonioxEvent {
    data class Update(val source: String, val translation: String) : SonioxEvent
    data class Finished(val source: String, val translation: String) : SonioxEvent
    data class Error(val message: String) : SonioxEvent
}

/**
 * Folds Soniox real-time responses into the running source text and its translation.
 * Final tokens are appended once; non-final tokens are only a preview and are replaced
 * wholesale by the next response (https://docs.soniox.com/stt/api-reference/websocket-api).
 *
 * The text is kept as paragraphs: a new one starts when someone speaks after a pause and when
 * the speaker changes (Soniox diarization labels every token, translation included). Once two
 * or more speakers were heard, each change of speaker is labeled "Speaker N:", from the start;
 * a conversation with one voice shows no labels.
 */
class SonioxTranscript {
    private val source = Side()
    private val translation = Side()

    /**
     * Someone speaks again after a pause: what follows starts a new paragraph, in the source
     * and in the translation. Called from the microphone thread, apply() from the socket's.
     */
    @Synchronized
    fun paragraphBreak() {
        source.paragraphBreak()
        translation.paragraphBreak()
    }

    @Synchronized
    fun apply(message: String): SonioxEvent {
        val root = Json.parseToJsonElement(message).jsonObject
        root["error_code"]?.let { code ->
            return SonioxEvent.Error(
                when (code.jsonPrimitive.content) {
                    "402" -> "Your Soniox balance is empty. Top it up at console.soniox.com."
                    "401" -> "Your Soniox API key is invalid. Check it in Settings."
                    else -> "Soniox ${code.jsonPrimitive.content}: ${root.string("error_message") ?: "unknown error"}"
                }
            )
        }

        val pendingSource = mutableListOf<Token>()
        val pendingTranslation = mutableListOf<Token>()
        root["tokens"]?.jsonArray?.forEach { element ->
            val token = element.jsonObject
            val text = token.string("text") ?: return@forEach
            // Endpoint-detection control token, not speech.
            if (text == END_TOKEN) return@forEach
            val isTranslation = token.string("translation_status") == "translation"
            val isFinal = token["is_final"]?.jsonPrimitive?.booleanOrNull == true
            val parsed = Token(text, token.string("speaker"))
            when {
                isTranslation && isFinal -> translation.finals.addToken(parsed, translation)
                isTranslation -> pendingTranslation += parsed
                isFinal -> source.finals.addToken(parsed, source)
                else -> pendingSource += parsed
            }
        }

        val sourceText = source.render(pendingSource)
        val translationText = translation.render(pendingTranslation)
        return if (root["finished"]?.jsonPrimitive?.booleanOrNull == true) {
            SonioxEvent.Finished(sourceText, translationText)
        } else {
            SonioxEvent.Update(sourceText, translationText)
        }
    }

    private data class Token(val text: String, val speaker: String?)

    private class Paragraph(val speaker: String?, val text: StringBuilder = StringBuilder())

    /** One side's text: the final paragraphs, and whether a pause is waiting for new words. */
    private class Side {
        val finals = mutableListOf<Paragraph>()
        var breakPending = false

        fun paragraphBreak() {
            breakPending = finals.isNotEmpty()
        }

        /** The final text with this response's preview words after it. */
        fun render(pending: List<Token>): String {
            if (pending.isEmpty()) return text(finals)
            val preview = finals.mapTo(mutableListOf()) { Paragraph(it.speaker, StringBuilder(it.text)) }
            val previewState = Side().also { it.breakPending = breakPending }
            pending.forEach { preview.addToken(it, previewState) }
            return text(preview)
        }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val END_TOKEN = "<end>"
        const val PARAGRAPH = "\n\n"

        fun MutableList<Paragraph>.addToken(token: Token, side: Side) {
            val last = lastOrNull()
            // A lone space neither opens a paragraph nor tells who speaks.
            if (token.text.isBlank()) {
                last?.text?.append(token.text)
                return
            }
            val speakerChanged = last != null && token.speaker != null && token.speaker != last.speaker
            if (last == null || side.breakPending || speakerChanged) {
                add(Paragraph(token.speaker ?: last?.speaker, StringBuilder(token.text.trimStart())))
                side.breakPending = false
            } else {
                last.text.append(token.text)
            }
        }

        fun text(paragraphs: List<Paragraph>): String {
            val shown = paragraphs.filter { it.text.isNotBlank() }
            val labeled = shown.mapNotNull { it.speaker }.distinct().size >= 2
            return shown.mapIndexed { i, paragraph ->
                val body = paragraph.text.toString().trim()
                val speakerChanged = i == 0 || shown[i - 1].speaker != paragraph.speaker
                if (labeled && paragraph.speaker != null && speakerChanged) "Speaker ${paragraph.speaker}: $body" else body
            }.joinToString(PARAGRAPH)
        }
    }
}
