package com.petroff.synchra.config

interface ConfigRepository {
    fun getConfig(): AppConfig
    fun setApiKey(key: String)
    /** Picking the target's language swaps the two, so both sides never match. */
    fun setSourceLanguage(code: String)
    /** Picking the source's language swaps the two, so both sides never match. */
    fun setTargetLanguage(code: String)
    fun swapLanguages()
    fun setGeneralContext(context: String)
    fun setConversationContext(context: String)
    fun setSonioxApiKey(key: String)
    fun setCrashReportsConsent(consent: Boolean)
    fun setMode(mode: Mode)
    fun setShowSilenceIcon(show: Boolean)
    fun setSessionLimitMinutes(minutes: Int)
    fun setTextSizeSp(sp: Int)
}

class DefaultConfigRepository(
    private val store: SettingsStore,
    private val defaultApiBaseUrl: String,
) : ConfigRepository {

    override fun getConfig(): AppConfig = AppConfig(
        apiBaseUrl = store.getString(KEY_API_BASE_URL, defaultApiBaseUrl),
        apiKey = store.getString(KEY_API_KEY, "").ifBlank { null },
        sourceLanguage = source(),
        targetLanguage = target(),
        recentLanguages = recentCodes().mapNotNull(::languageForCode),
        // Capped on read too: values saved before the limits existed may be longer.
        generalContext = store.getString(KEY_GENERAL_CONTEXT, "").take(MAX_GENERAL_CONTEXT_CHARS),
        conversationContext = store.getString(KEY_CONVERSATION_CONTEXT, "").take(MAX_CONVERSATION_CONTEXT_CHARS),
        sonioxApiKey = store.getString(KEY_SONIOX_API_KEY, "").ifBlank { null },
        crashReportsConsent = when (store.getString(KEY_CRASH_REPORTS, "")) {
            CONSENT_YES -> true
            CONSENT_NO -> false
            else -> null
        },
        mode = mode(),
        showSilenceIcon = store.getString(KEY_SILENCE_ICON, "") != SILENCE_ICON_HIDDEN,
        sessionLimitMinutes = store.getString(KEY_SESSION_LIMIT, "").toIntOrNull()
            ?.takeIf { it in SESSION_LIMIT_CHOICES } ?: DEFAULT_SESSION_LIMIT_MINUTES,
        textSizeSp = store.getString(KEY_TEXT_SIZE, "").toIntOrNull()
            ?.takeIf { it in TEXT_SIZE_CHOICES } ?: DEFAULT_TEXT_SIZE_SP,
    )

    override fun setApiKey(key: String) {
        store.putString(KEY_API_KEY, key.trim())
        // The old Kimi key is useless now and shouldn't linger in storage.
        store.putString(KEY_LEGACY_KIMI_API_KEY, "")
    }

    override fun setSourceLanguage(code: String) {
        val language = languageForCode(code) ?: return
        if (language == target()) swapLanguages() else store.putString(KEY_SOURCE_LANGUAGE, language.code)
        remember(language)
    }

    override fun setTargetLanguage(code: String) {
        val language = languageForCode(code) ?: return
        if (language == source()) swapLanguages() else store.putString(KEY_TARGET_LANGUAGE, language.code)
        remember(language)
    }

    override fun swapLanguages() {
        val (source, target) = source() to target()
        store.putString(KEY_SOURCE_LANGUAGE, target.code)
        store.putString(KEY_TARGET_LANGUAGE, source.code)
    }

    override fun setGeneralContext(context: String) =
        store.putString(KEY_GENERAL_CONTEXT, context.take(MAX_GENERAL_CONTEXT_CHARS))

    override fun setConversationContext(context: String) =
        store.putString(KEY_CONVERSATION_CONTEXT, context.take(MAX_CONVERSATION_CONTEXT_CHARS))

    // Keys go into HTTP headers, where a pasted line break is illegal.
    override fun setSonioxApiKey(key: String) = store.putString(KEY_SONIOX_API_KEY, key.trim())

    override fun setCrashReportsConsent(consent: Boolean) =
        store.putString(KEY_CRASH_REPORTS, if (consent) CONSENT_YES else CONSENT_NO)

    override fun setMode(mode: Mode) =
        store.putString(KEY_MODE, if (mode == Mode.FREE) MODE_FREE else MODE_PAID)

    override fun setSessionLimitMinutes(minutes: Int) = store.putString(KEY_SESSION_LIMIT, minutes.toString())

    override fun setTextSizeSp(sp: Int) = store.putString(KEY_TEXT_SIZE, sp.toString())

    override fun setShowSilenceIcon(show: Boolean) =
        store.putString(KEY_SILENCE_ICON, if (show) SILENCE_ICON_SHOWN else SILENCE_ICON_HIDDEN)

    // Users who already pay for Soniox keep the paid engines after the update; everyone else
    // starts with the free, on-device ones.
    private fun mode(): Mode = when (store.getString(KEY_MODE, "")) {
        MODE_FREE -> Mode.FREE
        MODE_PAID -> Mode.PAID
        else -> if (store.getString(KEY_SONIOX_API_KEY, "").isBlank()) Mode.FREE else Mode.PAID
    }

    private fun source() = languageForCode(store.getString(KEY_SOURCE_LANGUAGE, DEFAULT_SOURCE_LANGUAGE))
        ?: languageForCode(DEFAULT_SOURCE_LANGUAGE)!!

    private fun target() = languageForCode(store.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE))
        ?: languageForCode(DEFAULT_TARGET_LANGUAGE)!!

    private fun recentCodes(): List<String> =
        store.getString(KEY_RECENT_LANGUAGES, "").split(LIST_SEPARATOR).filter { it.isNotBlank() }
            .ifEmpty { listOf(DEFAULT_SOURCE_LANGUAGE, DEFAULT_TARGET_LANGUAGE) }

    private fun remember(language: Language) {
        val recent = (listOf(language.code) + recentCodes()).distinct().take(MAX_RECENT_LANGUAGES)
        store.putString(KEY_RECENT_LANGUAGES, recent.joinToString(LIST_SEPARATOR))
    }

    companion object {
        private const val KEY_API_BASE_URL = "api_base_url"
        // Renamed when translation moved from Kimi to Claude, so a stored Kimi key is never
        // sent to the Anthropic API.
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val KEY_LEGACY_KIMI_API_KEY = "api_key"
        // The old "language_pairs" / "selected_pair" keys only ever held English → Russian,
        // which is also the default, so they are simply no longer read.
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_RECENT_LANGUAGES = "recent_languages"
        private const val MAX_RECENT_LANGUAGES = 5
        private const val KEY_GENERAL_CONTEXT = "general_context"
        private const val KEY_CONVERSATION_CONTEXT = "conversation_context"
        private const val KEY_SONIOX_API_KEY = "soniox_api_key"
        private const val LIST_SEPARATOR = ","
        private const val KEY_CRASH_REPORTS = "crash_reports"
        private const val CONSENT_YES = "yes"
        private const val CONSENT_NO = "no"
        private const val KEY_MODE = "mode"
        private const val MODE_FREE = "free"
        private const val MODE_PAID = "paid"
        private const val KEY_SILENCE_ICON = "silence_icon"
        private const val KEY_SESSION_LIMIT = "session_limit_minutes"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val SILENCE_ICON_SHOWN = "shown"
        private const val SILENCE_ICON_HIDDEN = "hidden"
    }
}
