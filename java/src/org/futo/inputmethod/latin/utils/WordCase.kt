package org.futo.inputmethod.latin.utils

import java.util.Locale

/**
 * SwiftKey-style case cycling for the word at the cursor: pressing shift while the cursor
 * touches a word changes it to Title Case, then ALL CAPS, then lowercase, and round again.
 */
object WordCase {
    @JvmStatic
    fun next(word: String, locale: Locale): String {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return word
        val lower = word.lowercase(locale)
        val title = lower.replaceFirstChar { it.titlecase(locale) }
        val upper = word.uppercase(locale)
        return when {
            word == upper && letters.length > 1 -> lower   // ALL CAPS -> lowercase
            word == title -> if (letters.length > 1) upper else lower  // Title -> ALL CAPS
            else -> title                                   // lowercase or mixed -> Title
        }
    }
}
