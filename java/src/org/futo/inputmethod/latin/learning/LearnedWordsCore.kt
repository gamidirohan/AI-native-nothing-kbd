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
 * A personal typo: the user typed [from] and then replaced it with [to] (by deleting and
 * retyping, or by tapping [to] in the suggestion strip), for example "kr" -> "it" when a finger
 * lands one key off.
 */
@Serializable
data class LearnedCorrection(
    val from: String,
    val to: String,
    /** How many times the user made this replacement. */
    val count: Int,
    val lastMillis: Long
)

/** What is saved to disk. Older files are a plain list of [LearnedWord]. */
@Serializable
private data class LearnedData(
    val words: List<LearnedWord> = emptyList(),
    val corrections: List<LearnedCorrection> = emptyList()
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
    private val corrections = LinkedHashMap<String, LearnedCorrection>()

    // A word the user deleted (that was not an autocorrection), waiting to see what they type
    // instead.
    private var deletedWord: String? = null
    private var deletedAtMillis = 0L

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

    /** All learned corrections, most recent first. */
    fun allCorrections(): List<LearnedCorrection> = corrections.values.sortedByDescending { it.lastMillis }

    /**
     * The word [typed] should be autocorrected to, from the user's own past replacements, in the
     * same capitalization as [typed]. Null if there is none yet, or the user keeps [typed] as is.
     */
    fun correctionFor(typed: String): String? {
        if (isKept(typed)) return null
        val entry = corrections[key(typed)] ?: return null
        if (entry.count < APPLY_CORRECTION_AFTER) return null
        return matchCase(typed, entry.to)
    }

    /**
     * Records that the user replaced [from] with [to]. Ignored unless it looks like a typo of
     * the same word (see [isPlausibleTypo]), so rewording is not learned. Returns the entry.
     */
    fun recordReplacement(from: String, to: String, nowMillis: Long): LearnedCorrection? {
        val a = from.trim()
        val b = to.trim()
        if (!isLearnable(a) || !isLearnable(b)) return null
        if (key(a) == key(b) || !isPlausibleTypo(a, b)) return null

        val k = key(a)
        val previous = corrections.remove(k)
        // A different target starts counting again: the latest habit wins.
        val count = if (previous != null && key(previous.to) == key(b)) previous.count + 1 else 1
        val entry = LearnedCorrection(from = a, to = b, count = count, lastMillis = nowMillis)
        corrections[k] = entry
        trim()
        return entry
    }

    /**
     * Call for every word the keyboard commits. If the user deleted a word shortly before, the
     * committed word is taken as its replacement. Returns the updated correction, if any.
     */
    fun onWordCommitted(committed: String, nowMillis: Long): LearnedCorrection? {
        val deleted = deletedWord ?: return null
        val recent = nowMillis - deletedAtMillis <= REPLACE_WINDOW_MILLIS
        deletedWord = null
        if (!recent) return null
        return recordReplacement(deleted, committed, nowMillis)
    }

    fun removeCorrection(from: String): Boolean = corrections.remove(key(from)) != null

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
        val isLatestAutocorrection = typed != null && correctedTo != null
                && nowMillis - lastCorrectedAtMillis <= DELETE_WINDOW_MILLIS
                && deleted.trim() == correctedTo
        if (isLatestAutocorrection) {
            clearPendingAutocorrection()
            return typed
        }
        if (nowMillis - lastCorrectedAtMillis > DELETE_WINDOW_MILLIS) clearPendingAutocorrection()

        // Not an autocorrection: remember the word, in case the user now types a fix for it.
        if (isLearnable(deleted.trim())) {
            deletedWord = deleted.trim()
            deletedAtMillis = nowMillis
        }
        return null
    }

    fun clearPendingAutocorrection() {
        lastTyped = null
        lastCorrectedTo = null
        lastCorrectedAtMillis = 0L
    }

    fun remove(word: String): Boolean = words.remove(key(word)) != null

    fun clear() {
        words.clear()
        corrections.clear()
        deletedWord = null
        clearPendingAutocorrection()
    }

    fun toJson(): String = json.encodeToString(LearnedData(all(), allCorrections()))

    /**
     * Adds the saved words in [json] to whatever is already in memory, for example rejections
     * recorded before loading finished. Counts for the same word are added together. Invalid
     * input is ignored.
     */
    fun mergeJson(text: String) {
        val data = try {
            json.decodeFromString<LearnedData>(text)
        } catch (e: Exception) {
            // Files written before corrections were added are a plain list of words.
            try {
                LearnedData(words = json.decodeFromString<List<LearnedWord>>(text))
            } catch (e2: Exception) {
                LearnedData()
            }
        }
        mergeCorrections(data.corrections)
        val saved = data.words
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

    private fun mergeCorrections(saved: List<LearnedCorrection>) {
        saved.sortedBy { it.lastMillis }.forEach { entry ->
            if (!isLearnable(entry.from) || !isLearnable(entry.to) || entry.count <= 0) return@forEach
            val k = key(entry.from)
            val current = corrections[k]
            corrections[k] = when {
                current == null -> entry
                key(current.to) == key(entry.to) -> current.copy(
                    count = current.count + entry.count,
                    lastMillis = maxOf(current.lastMillis, entry.lastMillis)
                )
                // Different targets: keep whichever was used most recently.
                current.lastMillis >= entry.lastMillis -> current
                else -> entry
            }
        }
    }

    // Drop the least recently used entries once a list is full.
    private fun trim() {
        if (words.size > MAX_WORDS) all().drop(MAX_WORDS).forEach { words.remove(key(it.word)) }
        if (corrections.size > MAX_WORDS) allCorrections().drop(MAX_WORDS).forEach { corrections.remove(key(it.from)) }
    }

    companion object {
        const val STOP_AUTOCORRECT_AFTER = 1
        /** A personal typo is autocorrected once the user has fixed it this many times. */
        const val APPLY_CORRECTION_AFTER = 2
        /** How long after deleting a word the next word still counts as its replacement. */
        const val REPLACE_WINDOW_MILLIS = 30_000L
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

        private val json = Json { ignoreUnknownKeys = true }

        // Key positions on a QWERTY layout, rows offset like a real keyboard.
        private val keyPositions: Map<Char, Pair<Int, Double>> = buildMap {
            listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5).forEachIndexed { row, (keys, offset) ->
                keys.forEachIndexed { i, c -> put(c, row to i + offset) }
            }
        }

        /** True if [a] and [b] are next to each other (or the same) on a QWERTY keyboard. */
        fun isNearbyKey(a: Char, b: Char): Boolean {
            if (a == b) return true
            val pa = keyPositions[a] ?: return false
            val pb = keyPositions[b] ?: return false
            return kotlin.math.abs(pa.first - pb.first) <= 1 && kotlin.math.abs(pa.second - pb.second) <= 1.0
        }

        /**
         * True if [to] looks like the word the user meant when typing [from]: a finger slip
         * (same length, every changed letter one key away, like "kr" -> "it"), or a small edit
         * (one change per three letters, like "whys" -> "why's" or "teh" -> "the"). Rewording,
         * such as "car" -> "bus", is not a typo and is not learned.
         */
        fun isPlausibleTypo(from: String, to: String): Boolean {
            val a = from.lowercase(Locale.ROOT)
            val b = to.lowercase(Locale.ROOT)
            if (a == b) return false
            if (a.length == b.length && a.indices.all { isNearbyKey(a[it], b[it]) }) return true
            val allowed = maxOf(1, b.length / 3)
            return editDistance(a, b) <= allowed
        }

        /** Damerau-Levenshtein distance (insert, delete, replace, swap neighbours). */
        fun editDistance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
            return d[a.length][b.length]
        }

        /** Applies the capitalization of [typed] (all caps, or first letter) to [word]. */
        fun matchCase(typed: String, word: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            else -> word
        }

        private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }
}
