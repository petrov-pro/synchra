package com.petroff.synchra.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The body of a summary in blocks: what the meeting was about, a short account of it, and what
 * was decided. Stored as JSON in the record's summary text; summaries saved before blocks
 * existed are plain text, and [parse] returns null for them.
 */
data class SummarySections(
    val topic: String,
    val overview: String,
    val decisions: List<String>,
) {
    fun encode(): String = buildJsonObject {
        put(TOPIC, topic)
        put(OVERVIEW, overview)
        put(DECISIONS, JsonArray(decisions.map(::JsonPrimitive)))
    }.toString()

    /** For copying: the title, then each block under its heading. */
    fun toPlainText(title: String): String = buildString {
        append(title).append("\n\n")
        append(TOPIC_HEADING).append('\n').append(topic).append("\n\n")
        append(OVERVIEW_HEADING).append('\n').append(overview).append("\n\n")
        append(DECISIONS_HEADING).append('\n')
        append(decisionLines().joinToString("\n"))
    }

    /** Decisions as bullet lines, or a plain note that there were none. */
    fun decisionLines(): List<String> =
        decisions.map { "• $it" }.ifEmpty { listOf(NO_DECISIONS) }

    companion object {
        const val TOPIC_HEADING = "Topic"
        const val OVERVIEW_HEADING = "Overview"
        const val DECISIONS_HEADING = "Decisions and results"
        const val NO_DECISIONS = "No decisions recorded."

        private const val TOPIC = "topic"
        private const val OVERVIEW = "overview"
        private const val DECISIONS = "decisions"

        fun parse(stored: String): SummarySections? {
            val json = runCatching { Json.parseToJsonElement(stored) }.getOrNull() as? JsonObject ?: return null
            val topic = json[TOPIC]?.jsonPrimitive?.contentOrNull ?: return null
            val overview = json[OVERVIEW]?.jsonPrimitive?.contentOrNull ?: return null
            val decisions = (json[DECISIONS] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return null
            return SummarySections(topic, overview, decisions)
        }
    }
}
