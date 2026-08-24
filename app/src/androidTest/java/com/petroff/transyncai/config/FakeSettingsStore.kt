package com.petroff.transyncai.config

// Duplicated from app/src/test/java/com/petroff/transyncai/config/FakeSettingsStore.kt
// (Task 4's unit tests). The unit-test (`test`) and instrumented-test (`androidTest`) source
// sets are separate Gradle compilation units, so a class in one is not visible to the other.
// E2ETranslationFlowTest (Task 12) needs an in-memory SettingsStore double here.
class FakeSettingsStore : SettingsStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun contains(key: String) = strings.containsKey(key) || ints.containsKey(key)
}
