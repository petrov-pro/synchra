package com.petroff.transyncai.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String = "kimi-k2.5",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @kotlinx.serialization.SerialName("chat_template_kwargs")
    val chatTemplateKwargs: Map<String, Boolean> = mapOf("thinking" to false),
)

@Serializable
private data class ChatCompletionResponse(val choices: List<Choice>) {
    @Serializable
    data class Choice(val message: ChatMessage)
}

class KimiTranslationClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
) : Translator {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // model/temperature/chatTemplateKwargs are set via defaults, not overridden per-call
    }

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider() ?: return@withContext Result.failure(
            IllegalStateException("Kimi API key is not set")
        )

        runCatching {
            val requestBody = ChatCompletionRequest(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "You are a real-time interpreter. Translate CURRENT from " +
                            "$sourceLang to $targetLang. Use CONTEXT only to disambiguate. " +
                            "Reply with only the translation, no extra text.",
                    ),
                    ChatMessage(role = "user", content = "CONTEXT: $context\nCURRENT: $currentPhrase"),
                ),
            )

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(json.encodeToString(ChatCompletionRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Kimi API request failed: HTTP ${response.code}")
                }
                val bodyText = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
                parsed.choices.first().message.content.trim()
            }
        }
    }
}
