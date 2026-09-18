package com.ai.harnessdroid.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app language for this non-AppCompat Compose UI: persists the chosen
 * language tag ("", "en", "fr", "zh", "ar") and wraps the activity base
 * context so stringResource() resolves against the pinned locale.
 * "" = follow the system locale (the default).
 *
 * The choice is UI-only by design: agent answers, taught memories and the
 * OSP knowledge corpus stay in whatever language they were produced in.
 * On API 33+ the manifest's localeConfig additionally exposes the same
 * languages in the system per-app language settings.
 */
object LocaleManager {
    private const val PREFS = "ui_locale"
    private const val KEY = "language_tag"

    /** Supported tags; labels are shown untranslated (own-language names). */
    val LANGUAGES = listOf("en", "fr", "zh", "ar")

    /** Own-language display name for a menu entry ("Français", "中文", …). */
    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "fr" -> "Français"
        "zh" -> "中文"
        "ar" -> "العربية"
        else -> tag
    }

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).apply()
    }

    /** Wraps [context] with the persisted locale; no-op when following system. */
    fun wrap(context: Context): Context {
        val tag = load(context)
        if (tag.isBlank()) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        // setLocale also derives the layout direction — Arabic flips to RTL.
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
