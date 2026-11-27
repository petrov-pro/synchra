package com.petroff.synchra.config

// Both contexts go to Soniox together, and Soniox rejects a context over ~8,000 tokens
// (~10,000 characters): these caps keep the pair under that even for dense text.
const val MAX_GENERAL_CONTEXT_CHARS = 6_000
const val MAX_CONVERSATION_CONTEXT_CHARS = 2_000

/** Session time limit choices in minutes; 0 = off. */
val SESSION_LIMIT_CHOICES = listOf(0, 30, 60, 120)
const val DEFAULT_SESSION_LIMIT_MINUTES = 60

/** "30 min", "1 h", "2 h". */
fun limitLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

/** Live and saved text size choices in sp, smallest first, with their Settings labels. */
val TEXT_SIZE_CHOICES = listOf(18, 24, 30, 36)
val TEXT_SIZE_LABELS = listOf("S", "M", "L", "XL")
const val DEFAULT_TEXT_SIZE_SP = 24

data class AppConfig(
    // Anthropic API (Claude), kept for conversation summaries.
    val apiBaseUrl: String,
    val apiKey: String?,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    // Most recently picked first, for the top of the language picker.
    val recentLanguages: List<Language> = listOf(sourceLanguage, targetLanguage),
    // Standing background the customer sets once in Settings (e.g. domain/terminology hints)
    // and general-purpose enough to apply to any conversation.
    val generalContext: String = "",
    // Per-conversation context, set via the Main screen's Context popup. Sent after
    // generalContext (see LiveSession.start); persists across app restarts like everything
    // else here, but is meant to be replaced each new conversation.
    val conversationContext: String = "",
    val sonioxApiKey: String? = null,
    // Anonymous crash reports (Crashlytics): null until the user has answered the question.
    val crashReportsConsent: Boolean? = null,
    val mode: Mode = Mode.PAID,
    // A small icon in the corner of the live text while nobody speaks.
    val showSilenceIcon: Boolean = true,
    // Paid mode: a session with no touch in the app for this long pauses and asks to continue
    // (a forgotten session would keep paying Soniox). 0 = off.
    val sessionLimitMinutes: Int = DEFAULT_SESSION_LIMIT_MINUTES,
    // Size of the source and translation text, live and in saved conversations.
    val textSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
) {
    val selectedLanguagePair: LanguagePair
        get() = LanguagePair(sourceLanguage.code, sourceLanguage.name, targetLanguage.code, targetLanguage.name)
}
