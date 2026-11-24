package com.petroff.transyncai.summary

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
                    "You summarize a recorded conversation for the person who recorded it. Write " +
                        "everything in $targetLanguageName. The title is at most 60 characters, one " +
                        "line. The summary is a few short paragraphs or bullet points with the key " +
                        "points, decisions and action items. Reply only with the JSON."
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
        val title = json["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val summary = json["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank() || summary.isBlank()) error("Claude returned an empty title or summary")
        return ConversationSummary(title, summary)
    }

    companion object {
        private const val TAG = "TransyncAI/Claude"
        const val MODEL = "claude-haiku-5-5"

        private val SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(mapOf("title" to mapOf("type" to "string"), "summary" to mapOf("type" to "string"))),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("title", "summary")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }
}
