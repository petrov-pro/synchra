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
 */
class SonioxTranscript {
    private val finalSource = StringBuilder()
    private val finalTranslation = StringBuilder()

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

        val pendingSource = StringBuilder()
        val pendingTranslation = StringBuilder()
        root["tokens"]?.jsonArray?.forEach { element ->
            val token = element.jsonObject
            val text = token.string("text") ?: return@forEach
            // Endpoint-detection control token, not speech.
            if (text == END_TOKEN) return@forEach
            val isTranslation = token.string("translation_status") == "translation"
            val isFinal = token["is_final"]?.jsonPrimitive?.booleanOrNull == true
            when {
                isTranslation && isFinal -> finalTranslation.append(text)
                isTranslation -> pendingTranslation.append(text)
                isFinal -> finalSource.append(text)
                else -> pendingSource.append(text)
            }
        }

        val source = (finalSource.toString() + pendingSource).trim()
        val translation = (finalTranslation.toString() + pendingTranslation).trim()
        return if (root["finished"]?.jsonPrimitive?.booleanOrNull == true) {
            SonioxEvent.Finished(source, translation)
        } else {
            SonioxEvent.Update(source, translation)
        }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val END_TOKEN = "<end>"
    }
}
