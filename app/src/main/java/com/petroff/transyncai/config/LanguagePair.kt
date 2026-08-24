package com.petroff.transyncai.config

data class LanguagePair(
    val sourceCode: String,
    val sourceName: String,
    val targetCode: String,
    val targetName: String,
)

val DEFAULT_LANGUAGE_PAIRS = listOf(
    LanguagePair(sourceCode = "en", sourceName = "English", targetCode = "ru", targetName = "Russian"),
)
