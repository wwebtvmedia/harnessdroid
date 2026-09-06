package com.ai.harnessdroid.llm

import android.content.Context
import android.content.SharedPreferences

class LLMConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("llm_config", Context.MODE_PRIVATE)

    var useTree4Five: Boolean
        get() = prefs.getBoolean("use_tree4five", true)
        set(value) = prefs.edit().putBoolean("use_tree4five", value).apply()

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_url", value).apply()

    var customApiKey: String
        get() = prefs.getString("custom_api_key", "") ?: ""
        set(value) = prefs.edit().putString("custom_api_key", value).apply()

    var customApiType: String
        get() = prefs.getString("custom_api_type", "OpenAI") ?: "OpenAI"
        set(value) = prefs.edit().putString("custom_api_type", value).apply()
}
