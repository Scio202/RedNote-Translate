package dev.leo.rednotetrans

import android.content.Context
import android.content.SharedPreferences

/** Single source of truth for settings, read live by both the UI and the overlay service. */
object Prefs {
    const val KEY_ENABLED = "enabled"
    const val KEY_LANG = "lang"
    const val KEY_OPACITY = "opacity"
    const val KEY_THEME = "theme"
    const val KEY_CARD_NUDGE = "cardNudge"
    const val KEY_TINT = "tint"
    const val KEY_CARD_PAD = "cardPad"
    const val KEY_RADIUS = "radius"
    const val KEY_TEXT_SCALE = "textScale"
    const val KEY_MIN_LEN = "minLen"
    const val KEY_BOLD = "bold"
    const val KEY_SAMPLE = "sampleBg"
    const val KEY_ENGINE = "engine"
    const val KEY_DEEPSEEK_WARNED = "deepseekWarned"

    /** Translation back ends. On-device stays the fallback for the other two. */
    const val ENGINE_ON_DEVICE = "ondevice"
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DEEPSEEK = "deepseek"

    /** Overlay colours. Dark or light, chosen outright - no per-page guessing. */
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    val LANGUAGES = listOf(
        "en" to "English 🇺🇸",
        "es" to "Español 🇪🇸",
        "fr" to "Français 🇫🇷",
        "de" to "Deutsch 🇩🇪",
        "pt" to "Português 🇵🇹",
        "it" to "Italiano 🇮🇹",
        "ru" to "Русский 🇷🇺",
        "ja" to "日本語 🇯🇵",
        "ko" to "한국어 🇰🇷",
        "vi" to "Tiếng Việt 🇻🇳",
        "th" to "ไทย 🇹🇭",
        "id" to "Bahasa Indonesia 🇮🇩",
        "hi" to "हिन्दी 🇮🇳",
        // The isolate keeps the flag after the name; without it a right-to-left script
        // drags the emoji round to the other side.
        "ar" to "⁦العربية⁩ 🇸🇦",
    )

    fun of(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("rednotetrans", Context.MODE_PRIVATE)
}

// Top-level so they are plain extensions in this package - member extensions inside an
// object cannot be imported and used as extensions.

var SharedPreferences.enabled: Boolean
    get() = getBoolean(Prefs.KEY_ENABLED, true)
    set(v) { edit().putBoolean(Prefs.KEY_ENABLED, v).apply() }

var SharedPreferences.lang: String
    get() = getString(Prefs.KEY_LANG, "en") ?: "en"
    set(v) { edit().putString(Prefs.KEY_LANG, v).apply() }

/** Overlay background opacity, 0.5f..1f. Below 1 the original text shows through faintly. */
var SharedPreferences.opacity: Float
    get() = getFloat(Prefs.KEY_OPACITY, 1f)
    set(v) { edit().putFloat(Prefs.KEY_OPACITY, v).apply() }

/** Whether labels have a background box at all. Off leaves bare text over the artwork. */
var SharedPreferences.tint: Boolean
    get() = getBoolean(Prefs.KEY_TINT, true)
    set(v) { edit().putBoolean(Prefs.KEY_TINT, v).apply() }

/**
 * Extra height in dp on grid-card backgrounds only. Those titles have no node to measure,
 * so a taller box is the way to cover a Chinese title that runs longer than its translation.
 */
var SharedPreferences.cardPad: Float
    get() = getFloat(Prefs.KEY_CARD_PAD, 0f)
    set(v) { edit().putFloat(Prefs.KEY_CARD_PAD, v).apply() }

/** Corner radius in dp for label backgrounds. */
var SharedPreferences.radius: Float
    get() = getFloat(Prefs.KEY_RADIUS, 6f)
    set(v) { edit().putFloat(Prefs.KEY_RADIUS, v).apply() }

/** Multiplier on the matched source text size. */
var SharedPreferences.textScale: Float
    get() = getFloat(Prefs.KEY_TEXT_SCALE, 1f)
    set(v) { edit().putFloat(Prefs.KEY_TEXT_SCALE, v).apply() }

/** Skip strings shorter than this. Usernames are short; note text is not. */
var SharedPreferences.minLen: Int
    get() = getInt(Prefs.KEY_MIN_LEN, 1)
    set(v) { edit().putInt(Prefs.KEY_MIN_LEN, v).apply() }

/** Heavier weight, for reading over busy artwork. */
var SharedPreferences.bold: Boolean
    get() = getBoolean(Prefs.KEY_BOLD, false)
    set(v) { edit().putBoolean(Prefs.KEY_BOLD, v).apply() }

/** Set once the user ticks "don't show this again" on the DeepSeek warning. */
var SharedPreferences.deepseekWarned: Boolean
    get() = getBoolean(Prefs.KEY_DEEPSEEK_WARNED, false)
    set(v) { edit().putBoolean(Prefs.KEY_DEEPSEEK_WARNED, v).apply() }

var SharedPreferences.engine: String
    get() = getString(Prefs.KEY_ENGINE, Prefs.ENGINE_ON_DEVICE) ?: Prefs.ENGINE_ON_DEVICE
    set(v) { edit().putString(Prefs.KEY_ENGINE, v).apply() }

/**
 * Sample the real pixels under each label and paint the background to match, instead of a
 * fixed colour. Costs one throttled screenshot per scan, so it is opt-in.
 */
var SharedPreferences.sampleBg: Boolean
    get() = getBoolean(Prefs.KEY_SAMPLE, false)
    set(v) { edit().putBoolean(Prefs.KEY_SAMPLE, v).apply() }

var SharedPreferences.theme: String
    // Anything stored by the old auto mode reads as dark, which is what the grid feed is.
    get() = getString(Prefs.KEY_THEME, Prefs.THEME_DARK)
        ?.takeIf { it == Prefs.THEME_LIGHT } ?: Prefs.THEME_DARK
    set(v) { edit().putString(Prefs.KEY_THEME, v).apply() }

/**
 * Vertical nudge in dp for grid-card titles only. Those have no node of their own, so their
 * label is positioned relative to the author row - close, but off by however much padding
 * RedNote happens to use. This is the calibration knob for that gap.
 */
var SharedPreferences.cardNudge: Float
    get() = getFloat(Prefs.KEY_CARD_NUDGE, -16f)
    set(v) { edit().putFloat(Prefs.KEY_CARD_NUDGE, v).apply() }

/** Puts every look and placement setting back to its default, leaving engine and language. */
fun SharedPreferences.resetAppearance() {
    edit()
        .remove(Prefs.KEY_THEME)
        .remove(Prefs.KEY_TINT)
        .remove(Prefs.KEY_OPACITY)
        .remove(Prefs.KEY_RADIUS)
        .remove(Prefs.KEY_TEXT_SCALE)
        .remove(Prefs.KEY_BOLD)
        .remove(Prefs.KEY_SAMPLE)
        .remove(Prefs.KEY_MIN_LEN)
        .remove(Prefs.KEY_CARD_PAD)
        .remove(Prefs.KEY_CARD_NUDGE)
        .apply()
}
