package com.petroff.transyncai.config

interface SettingsStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun contains(key: String): Boolean
}
