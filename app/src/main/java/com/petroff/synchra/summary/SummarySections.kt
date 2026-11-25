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

    /** A block as shown: its heading and its lines (one per decision). */
    data class Block(val heading: String, val lines: List<String>)

    /**
     * The blocks with something in them, in order. An empty one (often "Decisions and results")
     * is left out entirely rather than shown with a "nothing here" note.
     */
    fun blocks(): List<Block> = listOfNotNull(
        topic.takeIf { it.isNotBlank() }?.let { Block(TOPIC_HEADING, listOf(it)) },
        overview.takeIf { it.isNotBlank() }?.let { Block(OVERVIEW_HEADING, listOf(it)) },
        decisions.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { list -> Block(DECISIONS_HEADING, list.map { "• $it" }) },
    )

    /** For copying: the title, then each non-empty block under its heading. */
    fun toPlainText(title: String): String =
        (listOf(title) + blocks().map { "${it.heading}\n${it.lines.joinToString("\n")}" }).joinToString("\n\n")

    companion object {
        const val TOPIC_HEADING = "Topic"
        const val OVERVIEW_HEADING = "Overview"
        const val DECISIONS_HEADING = "Decisions and results"

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
