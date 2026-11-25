package com.petroff.synchra.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Downloads the English and Russian models (~30 MB each) on the first run: needs network. */
@RunWith(AndroidJUnit4::class)
class MlKitTranslatorInstrumentedTest {

    @Test
    fun translatesEnglishToRussian() = runBlocking {
        val translator = MlKitTranslator()
        try {
            translator.prepare("en", "ru").getOrThrow()
            val text = translator.translate("Good morning").getOrThrow()
            assertTrue("blank translation", text.isNotBlank())
            assertNotEquals("Good morning", text)
        } finally {
            translator.close()
        }
    }

    @Test
    fun onDeviceTargetsIncludeRussianAndUkrainian() {
        val codes = mlKitTargetCodes()
        assertTrue(codes.containsAll(listOf("ru", "uk", "en", "de")))
    }
}
