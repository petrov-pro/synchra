package com.petroff.transyncai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConfigRepositoryTest {

    private lateinit var store: FakeSettingsStore
    private lateinit var repo: ConfigRepository

    @Before
    fun setUp() {
        store = FakeSettingsStore()
        repo = DefaultConfigRepository(
            store = store,
            defaultApiBaseUrl = "https://api.anthropic.com",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
    }

    @Test
    fun `falls back to build-time defaults when nothing overridden`() {
        val config = repo.getConfig()
        assertEquals("https://api.anthropic.com", config.apiBaseUrl)
        assertNull(config.apiKey)
        assertEquals(DEFAULT_LANGUAGE_PAIRS, config.languagePairs)
        assertEquals("", config.generalContext)
        assertEquals("", config.conversationContext)
    }

    @Test
    fun `setGeneralContext persists and is returned by getConfig`() {
        repo.setGeneralContext("We're discussing a billing support case")
        assertEquals("We're discussing a billing support case", repo.getConfig().generalContext)
    }

    @Test
    fun `setConversationContext persists and is returned by getConfig`() {
        repo.setConversationContext("Talking about invoice #123")
        assertEquals("Talking about invoice #123", repo.getConfig().conversationContext)
    }

    @Test
    fun `setApiKey persists and is returned by getConfig`() {
        repo.setApiKey("sk-test-123")
        assertEquals("sk-test-123", repo.getConfig().apiKey)
    }

    @Test
    fun `setLanguagePairs overrides the default list`() {
        val custom = listOf(LanguagePair("en", "English", "es", "Spanish"))
        repo.setLanguagePairs(custom)
        assertEquals(custom, repo.getConfig().languagePairs)
    }

    @Test
    fun `setSelectedLanguagePairIndex is reflected in selectedLanguagePair`() {
        repo.setLanguagePairs(
            listOf(
                LanguagePair("en", "English", "ru", "Russian"),
                LanguagePair("en", "English", "es", "Spanish"),
            )
        )
        repo.setSelectedLanguagePairIndex(1)
        assertEquals("es", repo.getConfig().selectedLanguagePair.targetCode)
    }

    @Test
    fun `ignores a legacy Kimi key so it is never sent to the Anthropic API`() {
        store.putString("api_key", "sk-kimi-old")

        assertNull(repo.getConfig().apiKey)
    }

    @Test
    fun `saving a key wipes the legacy Kimi key`() {
        store.putString("api_key", "sk-kimi-old")

        repo.setApiKey("sk-ant-new")

        assertEquals("sk-ant-new", repo.getConfig().apiKey)
        assertEquals("", store.getString("api_key", ""))
    }

    @Test
    fun `the Soniox key is stored separately from the Anthropic key`() {
        repo.setApiKey("sk-ant-1")
        repo.setSonioxApiKey("snx-1")

        val config = repo.getConfig()
        assertEquals("snx-1", config.sonioxApiKey)
        assertEquals("sk-ant-1", config.apiKey)
    }
}
