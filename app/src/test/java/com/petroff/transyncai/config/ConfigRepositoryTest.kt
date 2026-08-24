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
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
    }

    @Test
    fun `falls back to build-time defaults when nothing overridden`() {
        val config = repo.getConfig()
        assertEquals(6, config.bufferSize)
        assertEquals("https://api.moonshot.ai/v1", config.apiBaseUrl)
        assertNull(config.apiKey)
        assertEquals(DEFAULT_LANGUAGE_PAIRS, config.languagePairs)
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
}
