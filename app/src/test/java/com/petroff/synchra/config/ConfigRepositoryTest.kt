package com.petroff.synchra.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        )
    }

    @Test
    fun `falls back to build-time defaults when nothing overridden`() {
        val config = repo.getConfig()
        assertEquals("https://api.anthropic.com", config.apiBaseUrl)
        assertNull(config.apiKey)
        assertEquals("en", config.sourceLanguage.code)
        assertEquals("ru", config.targetLanguage.code)
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
    fun `the chosen languages are saved`() {
        repo.setSourceLanguage("es")
        repo.setTargetLanguage("de")

        val config = repo.getConfig()
        assertEquals("es", config.sourceLanguage.code)
        assertEquals("de", config.targetLanguage.code)
        assertEquals(LanguagePair("es", "Spanish", "de", "German"), config.selectedLanguagePair)
    }

    @Test
    fun `swap exchanges the two languages`() {
        repo.swapLanguages()

        assertEquals("ru", repo.getConfig().sourceLanguage.code)
        assertEquals("en", repo.getConfig().targetLanguage.code)
    }

    @Test
    fun `picking the other side's language swaps instead of making both the same`() {
        repo.setTargetLanguage("en")

        assertEquals("ru", repo.getConfig().sourceLanguage.code)
        assertEquals("en", repo.getConfig().targetLanguage.code)

        repo.setSourceLanguage("en")

        assertEquals("en", repo.getConfig().sourceLanguage.code)
        assertEquals("ru", repo.getConfig().targetLanguage.code)
    }

    @Test
    fun `an unknown stored language falls back to English to Russian`() {
        store.putString("source_language", "xx")
        store.putString("target_language", "yy")

        assertEquals("en", repo.getConfig().sourceLanguage.code)
        assertEquals("ru", repo.getConfig().targetLanguage.code)
    }

    @Test
    fun `recent languages start with the defaults and put the latest pick first`() {
        assertEquals(listOf("en", "ru"), repo.getConfig().recentLanguages.map { it.code })

        repo.setTargetLanguage("de")
        repo.setSourceLanguage("es")
        repo.setTargetLanguage("ru")

        assertEquals(listOf("ru", "es", "de", "en"), repo.getConfig().recentLanguages.map { it.code })
    }

    @Test
    fun `at most five recent languages are kept`() {
        listOf("de", "fr", "it", "pl", "uk", "ja").forEach { repo.setTargetLanguage(it) }

        assertEquals(listOf("ja", "uk", "pl", "it", "fr"), repo.getConfig().recentLanguages.map { it.code })
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

    @Test
    fun `pasted keys lose surrounding spaces and line breaks`() {
        // A line break in a key used to crash Start (it goes into an HTTP header).
        repo.setSonioxApiKey("  snx-1\n")
        repo.setApiKey("\tsk-ant-1 \r\n")

        assertEquals("snx-1", repo.getConfig().sonioxApiKey)
        assertEquals("sk-ant-1", repo.getConfig().apiKey)
    }

    @Test
    fun `contexts are capped so together they fit Soniox's context limit`() {
        repo.setGeneralContext("g".repeat(10_000))
        repo.setConversationContext("c".repeat(10_000))

        val config = repo.getConfig()
        assertEquals(MAX_GENERAL_CONTEXT_CHARS, config.generalContext.length)
        assertEquals(MAX_CONVERSATION_CONTEXT_CHARS, config.conversationContext.length)
        assertTrue(MAX_GENERAL_CONTEXT_CHARS + MAX_CONVERSATION_CONTEXT_CHARS <= 8_000)
    }

    @Test
    fun `a context stored before the limit existed is capped when read`() {
        store.putString("general_context", "g".repeat(10_000))

        assertEquals(MAX_GENERAL_CONTEXT_CHARS, repo.getConfig().generalContext.length)
    }

    @Test
    fun `the silence icon is shown until turned off`() {
        assertEquals(true, repo.getConfig().showSilenceIcon)

        repo.setShowSilenceIcon(false)
        assertEquals(false, repo.getConfig().showSilenceIcon)

        repo.setShowSilenceIcon(true)
        assertEquals(true, repo.getConfig().showSilenceIcon)
    }

    @Test
    fun `crash reports are off and not yet asked about until the user answers`() {
        assertNull(repo.getConfig().crashReportsConsent)

        repo.setCrashReportsConsent(true)
        assertEquals(true, repo.getConfig().crashReportsConsent)

        repo.setCrashReportsConsent(false)
        assertEquals(false, repo.getConfig().crashReportsConsent)
    }

    @Test
    fun `mode defaults to PAID when a Soniox key is saved`() {
        store.putString("soniox_api_key", "k")
        assertEquals(Mode.PAID, repo.getConfig().mode)
    }

    @Test
    fun `mode defaults to FREE on a fresh install`() {
        assertEquals(Mode.FREE, repo.getConfig().mode)
    }

    @Test
    fun `setMode persists and keeps the keys`() {
        store.putString("soniox_api_key", "k")
        repo.setMode(Mode.FREE)
        val config = repo.getConfig()
        assertEquals(Mode.FREE, config.mode)
        assertEquals("k", config.sonioxApiKey)
    }

    @Test
    fun `an unknown stored mode falls back to the default`() {
        store.putString("mode", "garbage")
        assertEquals(Mode.FREE, repo.getConfig().mode)
    }

    @Test
    fun `session limit defaults to one hour`() {
        assertEquals(60, repo.getConfig().sessionLimitMinutes)
    }

    @Test
    fun `session limit is saved`() {
        repo.setSessionLimitMinutes(30)
        assertEquals(30, repo.getConfig().sessionLimitMinutes)
        repo.setSessionLimitMinutes(0)
        assertEquals(0, repo.getConfig().sessionLimitMinutes)
    }

    @Test
    fun `an unknown stored limit falls back to one hour`() {
        repo.setSessionLimitMinutes(45)
        assertEquals(60, repo.getConfig().sessionLimitMinutes)
    }

    @Test
    fun `text size defaults to medium and is saved`() {
        assertEquals(24, repo.getConfig().textSizeSp)
        repo.setTextSizeSp(36)
        assertEquals(36, repo.getConfig().textSizeSp)
    }

    @Test
    fun `an unknown stored text size falls back to medium`() {
        repo.setTextSizeSp(25)
        assertEquals(24, repo.getConfig().textSizeSp)
    }

    @Test
    fun `limit labels`() {
        assertEquals("30 min", limitLabel(30))
        assertEquals("1 h", limitLabel(60))
        assertEquals("2 h", limitLabel(120))
    }
}
