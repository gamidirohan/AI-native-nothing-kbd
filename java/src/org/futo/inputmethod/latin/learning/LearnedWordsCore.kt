package org.futo.inputmethod.latin.learning

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * A word the user has kept after the keyboard tried to autocorrect it, such as a
 * Telugu-English, Hindi-English or Tamil-English word typed in Latin letters.
 */
@Serializable
data class LearnedWord(
    /** The word as the user last typed it, with their capitalization. */
    val word: String,
    /** How many times the user undid or deleted an autocorrection of this word. */
    val rejections: Int,
    val lastRejectedMillis: Long
)

/**
 * The learning rules, without any Android code so they can be tested on a plain JVM.
 * [LearnedWords] wraps this with saving and the personal dictionary. Not thread-safe: the
 * caller serialises access.
 *
 * Rules:
 * - Undoing an autocorrection (backspace straight after it), or deleting the corrected word
 *   within [DELETE_WINDOW_MILLIS], counts as one rejection of the word the user typed.
 * - After [STOP_AUTOCORRECT_AFTER] rejections the word is no longer autocorrected.
 * - After [SAVE_TO_DICTIONARY_AFTER] rejections it should be added to the personal dictionary,
 *   which makes it appear in suggestions; [recordRejection] reports when that point is reached.
 */
class LearnedWordsCore {
    private val words = LinkedHashMap<String, LearnedWord>()

    // The most recent autocorrection, kept briefly so that deleting the corrected word can be
    // recognised as rejecting it.
    private var lastTyped: String? = null
    private var lastCorrectedTo: String? = null
    private var lastCorrectedAtMillis = 0L

    /** True if autocorrect should leave this typed word alone. */
    fun isKept(typed: String): Boolean =
        (words[key(typed)]?.rejections ?: 0) >= STOP_AUTOCORRECT_AFTER

    fun get(word: String): LearnedWord? = words[key(word)]

    /** All learned words, most recently rejected first. */
    fun all(): List<LearnedWord> = words.values.sortedByDescending { it.lastRejectedMillis }

    data class RejectionResult(
        val entry: LearnedWord,
        /** True exactly once: when the word has just reached [SAVE_TO_DICTIONARY_AFTER]. */
        val shouldSaveToDictionary: Boolean
    )

    /** Records that the user rejected an autocorrection of [typed]. Null if the word is ignored. */
    fun recordRejection(typed: String, nowMillis: Long): RejectionResult? {
        val word = typed.trim()
        if (!isLearnable(word)) return null

        val k = key(word)
        val previous = words.remove(k)
        val entry = LearnedWord(
            word = word,
            rejections = (previous?.rejections ?: 0) + 1,
            lastRejectedMillis = nowMillis
        )
        words[k] = entry
        trim()

        return RejectionResult(entry, entry.rejections == SAVE_TO_DICTIONARY_AFTER)
    }

    /** Call when the keyboard autocorrects [typed] into [correctedTo]. */
    fun onAutocorrected(typed: String, correctedTo: String, nowMillis: Long) {
        lastTyped = typed
        lastCorrectedTo = correctedTo
        lastCorrectedAtMillis = nowMillis
    }

    /**
     * Call when the user starts deleting a whole word with backspace. Returns the word the user
     * had originally typed if [deleted] is the result of the most recent autocorrection, made
     * within [DELETE_WINDOW_MILLIS]; otherwise null.
     */
    fun onWordDeleted(deleted: String, nowMillis: Long): String? {
        val typed = lastTyped
        val correctedTo = lastCorrectedTo
        if (typed == null || correctedTo == null) return null
        if (nowMillis - lastCorrectedAtMillis > DELETE_WINDOW_MILLIS) {
            clearPendingAutocorrection()
            return null
        }
        if (deleted.trim() != correctedTo) return null
        clearPendingAutocorrection()
        return typed
    }

    fun clearPendingAutocorrection() {
        lastTyped = null
        lastCorrectedTo = null
        lastCorrectedAtMillis = 0L
    }

    fun remove(word: String): Boolean = words.remove(key(word)) != null

    fun clear() {
        words.clear()
        clearPendingAutocorrection()
    }

    fun toJson(): String = Json.encodeToString(all())

    /**
     * Adds the saved words in [json] to whatever is already in memory, for example rejections
     * recorded before loading finished. Counts for the same word are added together. Invalid
     * input is ignored.
     */
    fun mergeJson(json: String) {
        val saved = try {
            Json.decodeFromString<List<LearnedWord>>(json)
        } catch (e: Exception) {
            emptyList()
        }
        // Oldest first, so the newest capitalization wins.
        saved.sortedBy { it.lastRejectedMillis }.forEach { entry ->
            if (!isLearnable(entry.word) || entry.rejections <= 0) return@forEach
            val k = key(entry.word)
            val current = words[k]
            words[k] = if (current == null) entry else current.copy(
                rejections = current.rejections + entry.rejections,
                lastRejectedMillis = maxOf(current.lastRejectedMillis, entry.lastRejectedMillis)
            )
        }
        trim()
    }

    // Drop the least recently rejected words once the list is full.
    private fun trim() {
        if (words.size <= MAX_WORDS) return
        all().drop(MAX_WORDS).forEach { words.remove(key(it.word)) }
    }

    companion object {
        const val STOP_AUTOCORRECT_AFTER = 1
        const val SAVE_TO_DICTIONARY_AFTER = 2
        const val DELETE_WINDOW_MILLIS = 60_000L
        const val MAX_WORDS = 500
        private const val MAX_WORD_LENGTH = 48

        fun key(word: String): String = word.trim().lowercase(Locale.ROOT)

        /**
         * Only plain words: at least two characters, starting with a letter, and no spaces,
         * digits or symbols other than an apostrophe or hyphen inside the word. Keeps out URLs,
         * numbers and pasted text. Vowel signs (combining marks) count as part of a word, since
         * Telugu, Hindi and Tamil words often end in one.
         */
        fun isLearnable(word: String): Boolean {
            if (word.length < 2 || word.length > MAX_WORD_LENGTH) return false
            if (!word.first().isLetter()) return false
            val last = word.last()
            if (!last.isLetter() && !isMark(last)) return false
            return word.all { it.isLetter() || isMark(it) || it == '\'' || it == '-' }
        }

        private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }
}
