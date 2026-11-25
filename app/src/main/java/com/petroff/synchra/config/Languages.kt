package com.petroff.synchra.config

/** A language Soniox can recognize and translate to or from (any pair works). */
data class Language(
    /** ISO code, as Soniox expects it in language_hints and translation. */
    val code: String,
    /** English name, shown in the app and stored with each conversation. */
    val name: String,
    /** The language's own name, so it can be found by typing it. */
    val nativeName: String,
)

/** https://docs.soniox.com/stt/concepts/supported-languages, alphabetical by English name. */
val SUPPORTED_LANGUAGES: List<Language> = listOf(
    Language("af", "Afrikaans", "Afrikaans"),
    Language("sq", "Albanian", "Shqip"),
    Language("ar", "Arabic", "العربية"),
    Language("az", "Azerbaijani", "Azərbaycan"),
    Language("eu", "Basque", "Euskara"),
    Language("be", "Belarusian", "Беларуская"),
    Language("bn", "Bengali", "বাংলা"),
    Language("bs", "Bosnian", "Bosanski"),
    Language("bg", "Bulgarian", "Български"),
    Language("ca", "Catalan", "Català"),
    Language("zh", "Chinese", "中文"),
    Language("hr", "Croatian", "Hrvatski"),
    Language("cs", "Czech", "Čeština"),
    Language("da", "Danish", "Dansk"),
    Language("nl", "Dutch", "Nederlands"),
    Language("en", "English", "English"),
    Language("et", "Estonian", "Eesti"),
    Language("fi", "Finnish", "Suomi"),
    Language("fr", "French", "Français"),
    Language("gl", "Galician", "Galego"),
    Language("de", "German", "Deutsch"),
    Language("el", "Greek", "Ελληνικά"),
    Language("gu", "Gujarati", "ગુજરાતી"),
    Language("he", "Hebrew", "עברית"),
    Language("hi", "Hindi", "हिन्दी"),
    Language("hu", "Hungarian", "Magyar"),
    Language("id", "Indonesian", "Bahasa Indonesia"),
    Language("it", "Italian", "Italiano"),
    Language("ja", "Japanese", "日本語"),
    Language("kn", "Kannada", "ಕನ್ನಡ"),
    Language("kk", "Kazakh", "Қазақ"),
    Language("ko", "Korean", "한국어"),
    Language("lv", "Latvian", "Latviešu"),
    Language("lt", "Lithuanian", "Lietuvių"),
    Language("mk", "Macedonian", "Македонски"),
    Language("ms", "Malay", "Bahasa Melayu"),
    Language("ml", "Malayalam", "മലയാളം"),
    Language("mr", "Marathi", "मराठी"),
    Language("no", "Norwegian", "Norsk"),
    Language("fa", "Persian", "فارسی"),
    Language("pl", "Polish", "Polski"),
    Language("pt", "Portuguese", "Português"),
    Language("pa", "Punjabi", "ਪੰਜਾਬੀ"),
    Language("ro", "Romanian", "Română"),
    Language("ru", "Russian", "Русский"),
    Language("sr", "Serbian", "Српски"),
    Language("sk", "Slovak", "Slovenčina"),
    Language("sl", "Slovenian", "Slovenščina"),
    Language("es", "Spanish", "Español"),
    Language("sw", "Swahili", "Kiswahili"),
    Language("sv", "Swedish", "Svenska"),
    Language("tl", "Tagalog", "Tagalog"),
    Language("ta", "Tamil", "தமிழ்"),
    Language("te", "Telugu", "తెలుగు"),
    Language("th", "Thai", "ไทย"),
    Language("tr", "Turkish", "Türkçe"),
    Language("uk", "Ukrainian", "Українська"),
    Language("ur", "Urdu", "اردو"),
    Language("vi", "Vietnamese", "Tiếng Việt"),
    Language("cy", "Welsh", "Cymraeg"),
)

const val DEFAULT_SOURCE_LANGUAGE = "en"
const val DEFAULT_TARGET_LANGUAGE = "ru"

fun languageForCode(code: String): Language? = SUPPORTED_LANGUAGES.firstOrNull { it.code == code }

/** The pair as the top bar shows it: capitalized codes, e.g. "En-Ru". */
fun shortPairLabel(source: Language, target: Language): String =
    "${source.code.shortForm()}-${target.code.shortForm()}"

/**
 * The same label for a saved conversation, which keeps only the English names. A name no
 * longer in [SUPPORTED_LANGUAGES] falls back to its first two letters.
 */
fun shortPairLabel(sourceName: String, targetName: String): String =
    "${shortFormOfName(sourceName)}-${shortFormOfName(targetName)}"

private fun shortFormOfName(name: String): String =
    (SUPPORTED_LANGUAGES.firstOrNull { it.name == name }?.code ?: name.take(2).lowercase()).shortForm()

private fun String.shortForm(): String = replaceFirstChar { it.uppercase() }

/**
 * Languages matching [query] by code, English name or own name, ignoring case: the exact code
 * first, then names starting with the query, then names merely containing it.
 */
fun searchLanguages(query: String, languages: List<Language> = SUPPORTED_LANGUAGES): List<Language> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return languages
    fun Language.names() = listOf(name.lowercase(), nativeName.lowercase())
    val byCode = languages.filter { it.code == q }
    val byPrefix = languages.filter { l -> l.names().any { it.startsWith(q) } }
    val byContains = languages.filter { l -> l.names().any { it.contains(q) } }
    return (byCode + byPrefix + byContains).distinct()
}
