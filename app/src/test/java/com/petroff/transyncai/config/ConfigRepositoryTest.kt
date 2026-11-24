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
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.anthropic.com",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
            defaultChunkSizeWords = 8,
        )
    }

    @Test
    fun `falls back to build-time defaults when nothing overridden`() {
        val config = repo.getConfig()
        assertEquals(6, config.bufferSize)
        assertEquals("https://api.anthropic.com", config.apiBaseUrl)
        assertNull(config.apiKey)
        assertEquals(DEFAULT_LANGUAGE_PAIRS, config.languagePairs)
        assertEquals(8, config.chunkSizeWords)
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
    fun `setChunkSizeWords overrides the default at runtime`() {
        repo.setChunkSizeWords(12)
        assertEquals(12, repo.getConfig().chunkSizeWords)
    }

    @Test
    fun `setApiKey persists and is returned by getConfig`() {
        repo.setApiKey("sk-test-123")
        assertEquals("sk-test-123", repo.getConfig().apiKey)
    }

    @Test
    fun `setBufferSize overrides the default at runtime`() {
        repo.setBufferSize(12)
        assertEquals(12, repo.getConfig().bufferSize)
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
    fun `defaults to the local engine with no Soniox key`() {
        val config = repo.getConfig()
        assertEquals(Engine.LOCAL, config.engine)
        assertNull(config.sonioxApiKey)
    }

    @Test
    fun `persists the engine choice and the Soniox key separately from the Anthropic key`() {
        repo.setApiKey("sk-ant-1")
        repo.setEngine(Engine.SONIOX)
        repo.setSonioxApiKey("snx-1")

        val config = repo.getConfig()
        assertEquals(Engine.SONIOX, config.engine)
        assertEquals("snx-1", config.sonioxApiKey)
        assertEquals("sk-ant-1", config.apiKey)
    }
}
