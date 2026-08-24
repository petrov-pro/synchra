package com.petroff.transyncai.config

class FakeSettingsStore : SettingsStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun contains(key: String) = strings.containsKey(key) || ints.containsKey(key)
}
