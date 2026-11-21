package org.futo.inputmethod.latin.uix.actions.ai

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey

/** Edit strength, stored as the [FormatLevel] ordinal. */
val AI_FORMAT_LEVEL = SettingsKey(
    intPreferencesKey("aiWriteFormatLevel"),
    FormatLevel.Balanced.ordinal
)

/** Language code from [AiTranslateLanguages] that the Translate style translates into. */
val AI_TRANSLATE_TARGET = SettingsKey(
    stringPreferencesKey("aiWriteTranslateTarget"),
    "es"
)

/**
 * Run the model on the GPU. On a Nothing Phone (1) this was about 2.2x faster than the CPU
 * (6.4 vs 2.4-3.1 tokens/sec; Fix all 9 s vs 20-26 s). Falls back to the CPU if the GPU fails.
 */
val AI_USE_GPU = SettingsKey(
    booleanPreferencesKey("aiWriteUseGpu"),
    true
)

/**
 * Keep the model in memory instead of loading it on first use. It is loaded in the background
 * when the keyboard opens and kept until the keyboard process ends, so AI Writing starts
 * without the load delay (about 1 s on the CPU, 12 s the first time on the GPU). Costs about
 * 1.5 GB of RAM while the keyboard runs.
 */
val AI_KEEP_LOADED = SettingsKey(
    booleanPreferencesKey("aiWriteKeepLoaded"),
    true
)

/** The user's own name as it appears in copied chats, so Reply writes as them. */
val AI_MY_CHAT_NAME = SettingsKey(
    stringPreferencesKey("aiWriteMyChatName"),
    ""
)

/** Only download the model on an unmetered connection (normally Wi-Fi). */
val AI_DOWNLOAD_WIFI_ONLY = SettingsKey(
    booleanPreferencesKey("aiWriteDownloadWifiOnly"),
    true
)

/**
 * When on, opening AI Writing with newly copied text on the clipboard translates that text
 * straight away, ready to insert. It runs when the panel opens, not in the background.
 */
val AI_AUTO_TRANSLATE_CLIPBOARD = SettingsKey(
    booleanPreferencesKey("aiWriteAutoTranslateClipboard"),
    false
)

/**
 * Languages the on-device model can translate into. Deliberately short: the 0.6B model was
 * tried on more (German, Italian, Hindi, Telugu, Tamil, Bengali) and either made frequent
 * mistakes or produced repeated nonsense, so those are not offered. Translating *from* Indian
 * languages into English was also unusable. See docs/AI_WRITING_ASSISTANT.md for the results.
 */
object AiTranslateLanguages {
    val languages = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "pt" to "Portuguese",
        "ja" to "Japanese"
    )

    fun name(code: String): String = languages.firstOrNull { it.first == code }?.second ?: "English"
}
