package com.petroff.transyncai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `all 60 Soniox languages are offered, each once, in alphabetical order`() {
        assertEquals(60, SUPPORTED_LANGUAGES.size)
        assertEquals(60, SUPPORTED_LANGUAGES.map { it.code }.toSet().size)
        assertEquals(SUPPORTED_LANGUAGES.sortedBy { it.name }, SUPPORTED_LANGUAGES)
    }

    @Test
    fun `codes are the ones Soniox expects`() {
        assertEquals("Norwegian", languageForCode("no")?.name)
        assertEquals("Hebrew", languageForCode("he")?.name)
        assertEquals("Tagalog", languageForCode("tl")?.name)
        assertNull(languageForCode("xx"))
    }

    @Test
    fun `a language is found by its English name, its own name or its code`() {
        for (query in listOf("russ", "RUSSIAN", "рус", "Русский", "ru")) {
            assertEquals(query, "ru", searchLanguages(query).first().code)
        }
    }

    @Test
    fun `an exact code match comes first, then names starting with the query`() {
        val found = searchLanguages("ar").map { it.code }

        assertEquals("ar", found.first())
        assertTrue(found.indexOf("ar") < found.indexOf("bg")) // "Bulgarian" only contains "ar"
    }

    @Test
    fun `an empty query lists every language`() {
        assertEquals(SUPPORTED_LANGUAGES, searchLanguages("  "))
    }

    @Test
    fun `nothing matches nonsense`() {
        assertTrue(searchLanguages("zzzz").isEmpty())
    }
}
