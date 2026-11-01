package com.blake.app

import android.content.Context

object Prefs {
    const val NAME = "blake_prefs"
    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val DEFAULT_BASE_URL = "https://blake-assistant.onrender.com"

    fun apiFor(context: Context): BlakeApi {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return BlakeApi(baseUrl, apiKey)
    }
}
