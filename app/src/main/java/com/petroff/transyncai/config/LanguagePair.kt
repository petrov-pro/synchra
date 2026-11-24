package com.petroff.transyncai.config

/** The two languages a session runs with; names go into history and summaries. */
data class LanguagePair(
    val sourceCode: String,
    val sourceName: String,
    val targetCode: String,
    val targetName: String,
)
