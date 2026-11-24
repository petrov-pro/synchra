package com.petroff.transyncai.translate

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translates via Claude Haiku 5.5 — chosen over Kimi K2.6 for cost (~$0.10/$0.50 vs
 * $0.95/$4.00 per 1M tokens) on what is a simple short-phrase EN→RU task.
 */
class ClaudeTranslationClient(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
    // One quick retry on a dropped connection/5xx; more would just delay live subtitles.
    private val maxRetries: Int = 1,
) : Translator {

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
            .timeout(Duration.ofSeconds(15))
            .build()
            .also {
                cachedKey = apiKey
                cachedClient = it
            }
    }

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider() ?: run {
            Log.w(TAG, "translate: no API key set, failing fast without a network call")
            return@withContext Result.failure(IllegalStateException("Anthropic API key is not set"))
        }

        runCatching {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024L)
                .system(
                    "You are a real-time interpreter. Translate CURRENT from $sourceLang to " +
                        "$targetLang. Use CONTEXT only to disambiguate. Reply with only the " +
                        "translation, no extra text."
                )
                // Translation needs no reasoning; thinking off (allowed at effort <= high) and the
                // lowest effort keep latency and output-token spend minimal.
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("CONTEXT: $context\nCURRENT: $currentPhrase")
                .build()

            Log.d(TAG, "translate: currentPhrase=\"$currentPhrase\"")
            val response = clientFor(apiKey).messages().create(params)
            response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("")
                .trim()
                .also { Log.d(TAG, "translate: -> \"$it\" (stop=${response.stopReason().orElse(null)})") }
        }
    }

    companion object {
        private const val TAG = "TransyncAI/Claude"
        const val MODEL = "claude-haiku-5-5"
    }
}
