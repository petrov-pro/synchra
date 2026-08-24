package com.petroff.transyncai.config

interface ConfigRepository {
    fun getConfig(): AppConfig
    fun setApiKey(key: String)
    fun setBufferSize(size: Int)
    fun setLanguagePairs(pairs: List<LanguagePair>)
    fun setSelectedLanguagePairIndex(index: Int)
}

class DefaultConfigRepository(
    private val store: SettingsStore,
    private val defaultBufferSize: Int,
    private val defaultApiBaseUrl: String,
    private val defaultLanguagePairs: List<LanguagePair>,
) : ConfigRepository {

    override fun getConfig(): AppConfig = AppConfig(
        bufferSize = store.getInt(KEY_BUFFER_SIZE, defaultBufferSize),
        apiBaseUrl = store.getString(KEY_API_BASE_URL, defaultApiBaseUrl),
        apiKey = store.getString(KEY_API_KEY, "").ifBlank { null },
        languagePairs = readLanguagePairs(),
        selectedLanguagePairIndex = store.getInt(KEY_SELECTED_PAIR, 0),
    )

    override fun setApiKey(key: String) = store.putString(KEY_API_KEY, key)

    override fun setBufferSize(size: Int) = store.putInt(KEY_BUFFER_SIZE, size)

    override fun setLanguagePairs(pairs: List<LanguagePair>) {
        val encoded = pairs.joinToString(PAIR_SEPARATOR) {
            listOf(it.sourceCode, it.sourceName, it.targetCode, it.targetName).joinToString(FIELD_SEPARATOR)
        }
        store.putString(KEY_LANGUAGE_PAIRS, encoded)
    }

    override fun setSelectedLanguagePairIndex(index: Int) = store.putInt(KEY_SELECTED_PAIR, index)

    private fun readLanguagePairs(): List<LanguagePair> {
        if (!store.contains(KEY_LANGUAGE_PAIRS)) return defaultLanguagePairs
        val raw = store.getString(KEY_LANGUAGE_PAIRS, "")
        if (raw.isBlank()) return defaultLanguagePairs
        return raw.split(PAIR_SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 4) null else LanguagePair(fields[0], fields[1], fields[2], fields[3])
        }
    }

    companion object {
        private const val KEY_BUFFER_SIZE = "buffer_size"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LANGUAGE_PAIRS = "language_pairs"
        private const val KEY_SELECTED_PAIR = "selected_pair"
        private const val PAIR_SEPARATOR = ";;"
        private const val FIELD_SEPARATOR = "|"
    }
}
