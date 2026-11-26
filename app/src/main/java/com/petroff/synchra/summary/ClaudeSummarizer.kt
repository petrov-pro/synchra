package com.petroff.synchra.summary

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Title + summary of a conversation from Claude Haiku 5.5, as structured JSON. */
class ClaudeSummarizer(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
    // Retries (and when not to retry) are decided by the caller.
    private val maxRetries: Int = 0,
) : Summarizer {

    // The key can change in Settings while the app runs; rebuild the SDK client only then.
    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    @Synchronized
    private fun clientFor(apiKey: String): AnthropicClient {
        cachedClient?.takeIf { apiKey == cachedKey }?.let { return it }
        cachedClient?.close()
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .maxRetries(maxRetries)
            // An hour-long conversation is a big request; don't give up on it early.
            .timeout(Duration.ofSeconds(60))
            .build()
            .also {
                cachedKey = apiKey
                cachedClient = it
            }
    }

    override suspend fun summarize(
        sourceText: String,
        translatedText: String,
        targetLanguageName: String,
        userContext: String,
        conversationContext: String,
    ): Result<ConversationSummary> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                SummaryException(SummaryException.Kind.INVALID_KEY, "Anthropic API key is not set")
            )
        }
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(2048L)
                .system(
                    "You summarize a conversation that the user recorded with their phone, for the " +
                        "user to read later. The user is often a listener in a meeting or call and may " +
                        "not have said anything. When several voices were heard, the transcript marks " +
                        "each change of voice with a label like \"Speaker 1:\"; the labels are " +
                        "told apart by sound, may now and then be wrong, and never say who the speaker " +
                        "is. Work out who each labeled speaker is from the conversation itself: they " +
                        "introduce themselves (\"I'm Maria\"), someone addresses them by name " +
                        "(\"John, what do you think?\" and Speaker 2 answers), or the participants " +
                        "listed in ABOUT THIS CONVERSATION fit what they say. When it is clear, use the " +
                        "name instead of \"Speaker N\"; when it is not, keep \"Speaker N\" rather " +
                        "than guess. Without labels you usually cannot tell who said what. Write every field in $targetLanguageName.\n\n" +
                        "Fill these fields:\n" +
                        "- title: a name for the meeting, at most 60 characters, one line.\n" +
                        "- topic: what the meeting was about, in one short sentence.\n" +
                        "- overview: a short account of what was discussed, 2 to 5 sentences.\n" +
                        "- decisions: the final decisions and results, and agreed next steps, one " +
                        "short item each. An empty list if nothing was decided; never invent one.\n\n" +
                        "Report only what the transcript says, as neutrally as it says it: \"the " +
                        "engineers said the work is done\", \"a speaker asked…\", \"it was agreed…\". " +
                        "Never attribute a statement, task or decision to the user, or to anyone named " +
                        "in the sections below, unless the transcript itself names them as the one who " +
                        "said or owns it. If it is unclear who owns an action item, say so instead of " +
                        "guessing.\n\n" +
                        "The user may add two background sections. GLOBAL CONTEXT applies to all their " +
                        "conversations: the user, their company, team, product or terms. ABOUT THIS CONVERSATION " +
                        "describes this meeting: " +
                        "topic, participants, terms; its participants are the likely speakers, but do " +
                        "not match a speaker to a participant unless the transcript makes it clear " +
                        "(see above). Use both sections only to understand the topic and to spell names and " +
                        "terms right: never summarize them or present them as something said. Reply " +
                        "only with the JSON."
                )
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(SCHEMA).build())
                        .build()
                )
                .addUserMessage(
                    buildString {
                        // Helps with names, terms and what the talk is about.
                        if (userContext.isNotBlank()) {
                            append("GLOBAL CONTEXT (about the user, their company, team or product; the user recorded this and may not speak in it):\n")
                            append(userContext).append("\n\n")
                        }
                        if (conversationContext.isNotBlank()) {
                            append("ABOUT THIS CONVERSATION (from the user):\n")
                            append(conversationContext).append("\n\n")
                        }
                        append("ORIGINAL:\n").append(sourceText)
                        if (translatedText.isNotBlank()) append("\n\nTRANSLATION:\n").append(translatedText)
                    }
                )
                .build()

            val response = clientFor(apiKey).messages().create(params)
            val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            Result.success(parse(text))
        } catch (e: AnthropicServiceException) {
            Log.e(TAG, "summary request failed: HTTP ${e.statusCode()}", e)
            Result.failure(SummaryException(classify(e), e.message ?: "HTTP ${e.statusCode()}", e))
        } catch (e: Exception) {
            Log.e(TAG, "summary request failed", e)
            Result.failure(SummaryException(SummaryException.Kind.TEMPORARY, e.message ?: "Summary failed", e))
        }
    }

    private fun classify(e: AnthropicServiceException): SummaryException.Kind {
        val status = e.statusCode()
        return when {
            status == 401 || status == 403 -> SummaryException.Kind.INVALID_KEY
            // An empty balance comes back as 400 "Your credit balance is too low…".
            status == 402 || (status in 400..499 && e.body().toString().contains("credit balance")) ->
                SummaryException.Kind.OUT_OF_CREDIT
            else -> SummaryException.Kind.TEMPORARY
        }
    }

    private fun parse(text: String): ConversationSummary {
        val json = Json.parseToJsonElement(text).jsonObject
        fun field(name: String) = json[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val title = field("title")
        val sections = SummarySections(
            topic = field("topic"),
            overview = field("overview"),
            decisions = json["decisions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }.orEmpty(),
        )
        if (title.isBlank() || sections.topic.isBlank() || sections.overview.isBlank()) {
            error("Claude returned an empty title, topic or overview")
        }
        return ConversationSummary(title, sections.encode())
    }

    companion object {
        private const val TAG = "Synchra/Claude"
        const val MODEL = "claude-haiku-5-5"

        private val SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "title" to mapOf("type" to "string"),
                        "topic" to mapOf("type" to "string"),
                        "overview" to mapOf("type" to "string"),
                        "decisions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    )
                ),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("title", "topic", "overview", "decisions")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }
}
