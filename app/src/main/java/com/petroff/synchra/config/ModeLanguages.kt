package com.petroff.synchra.config

/**
 * Spoken languages the on-device model (Nemotron 3.5 ASR streaming) handles: its
 * "transcription-ready" and "broad-coverage" locales, as the app's ISO codes. Its
 * "adaptation-ready" locales need fine-tuning and are left out.
 */
val FREE_SOURCE_CODES: Set<String> = setOf(
    "en", "es", "fr", "it", "pt", "nl", "de", "tr", "ru", "ar", "hi", "ja", "ko", "vi", "uk",
    "pl", "sv", "cs", "no", "da", "bg", "fi", "hr", "sk", "zh", "hu", "ro", "et",
)

fun sourceLanguagesFor(mode: Mode): List<Language> = when (mode) {
    Mode.PAID -> SUPPORTED_LANGUAGES
    Mode.FREE -> SUPPORTED_LANGUAGES.filter { it.code in FREE_SOURCE_CODES }
}

/** [onDeviceTargetCodes]: languages the on-device translator can translate into. */
fun targetLanguagesFor(mode: Mode, onDeviceTargetCodes: Set<String>): List<Language> = when (mode) {
    Mode.PAID -> SUPPORTED_LANGUAGES
    Mode.FREE -> SUPPORTED_LANGUAGES.filter { it.code in onDeviceTargetCodes }
}

fun isPairSupported(mode: Mode, source: Language, target: Language, onDeviceTargetCodes: Set<String>): Boolean =
    source in sourceLanguagesFor(mode) && target in targetLanguagesFor(mode, onDeviceTargetCodes)
