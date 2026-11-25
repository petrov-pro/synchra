package com.petroff.synchra.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeLanguagesTest {

    private fun lang(code: String) = languageForCode(code)!!

    @Test
    fun `paid supports every app language`() {
        assertEquals(SUPPORTED_LANGUAGES, sourceLanguagesFor(Mode.PAID))
        assertEquals(SUPPORTED_LANGUAGES, targetLanguagesFor(Mode.PAID, emptySet()))
    }

    @Test
    fun `free sources are the Nemotron set`() {
        val codes = sourceLanguagesFor(Mode.FREE).map { it.code }
        assertEquals(FREE_SOURCE_CODES, codes.toSet())
        assertTrue(codes.containsAll(listOf("en", "ru", "uk")))
        assertFalse("af" in codes)
    }

    @Test
    fun `every free source code is an app language`() {
        FREE_SOURCE_CODES.forEach { assertNotNull(it, languageForCode(it)) }
    }

    @Test
    fun `free targets follow the on-device list in app order`() {
        assertEquals(listOf("de", "ru"), targetLanguagesFor(Mode.FREE, setOf("ru", "de")).map { it.code })
    }

    @Test
    fun `pair support depends on the mode`() {
        assertFalse(isPairSupported(Mode.FREE, lang("af"), lang("ru"), setOf("ru")))
        assertTrue(isPairSupported(Mode.FREE, lang("en"), lang("ru"), setOf("ru")))
        assertFalse(isPairSupported(Mode.FREE, lang("en"), lang("ru"), setOf("de")))
        assertTrue(isPairSupported(Mode.PAID, lang("af"), lang("ru"), emptySet()))
    }
}
