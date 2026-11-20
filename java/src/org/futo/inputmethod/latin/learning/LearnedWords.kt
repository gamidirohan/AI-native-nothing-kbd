package org.futo.inputmethod.latin.learning

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.futo.inputmethod.compat.UserDictionaryCompatUtils
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import java.io.File

private const val TAG = "LearnedWords"
private const val FILE_NAME = "learned_words.json"

/** Frequency given to words added to the personal dictionary (the range is 1-255). */
private const val PERSONAL_DICTIONARY_FREQUENCY = 250

/**
 * Remembers words the user keeps after autocorrect changes them (see [LearnedWordsCore] for the
 * rules), stops autocorrecting them, and adds frequently kept ones to the personal dictionary so
 * they show up in suggestions.
 *
 * Called from the input thread (InputLogic) and from Suggest. Every method is cheap: all file
 * and content-provider work runs on a background dispatcher. Nothing is read or written while the
 * phone is locked after a restart (direct boot), because app storage is not available then.
 */
object LearnedWords {
    private val core = LearnedWordsCore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    private val _words = MutableStateFlow<List<LearnedWord>>(emptyList())

    /** The current list, for the settings page. */
    val words: StateFlow<List<LearnedWord>> = _words.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Loads the saved list. Safe to call more than once; it only loads once. */
    @JvmStatic
    fun load(context: Context) {
        appContext = context.applicationContext
        if (loaded) return
        scope.launch {
            val ctx = appContext ?: return@launch
            if (!ctx.isDirectBootUnlocked) return@launch
            val json = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
            val changedBeforeLoad = synchronized(core) {
                if (loaded) return@launch
                val hadEntries = core.all().isNotEmpty()
                if (json != null) core.mergeJson(json)
                loaded = true
                _words.value = core.all()
                hadEntries
            }
            // Words rejected before loading finished were not saved yet.
            if (changedBeforeLoad) save(ctx)
        }
    }

    /** True if autocorrect should leave [typed] as it is. */
    @JvmStatic
    fun isKept(typed: String?): Boolean {
        if (typed.isNullOrEmpty()) return false
        return synchronized(core) { core.isKept(typed) }
    }

    @JvmStatic
    fun onAutocorrected(typed: String, correctedTo: String) {
        synchronized(core) { core.onAutocorrected(typed, correctedTo, System.currentTimeMillis()) }
    }

    /** Returns the originally typed word if [deleted] was the latest autocorrection. */
    @JvmStatic
    fun onWordDeleted(deleted: String): String? =
        synchronized(core) { core.onWordDeleted(deleted, System.currentTimeMillis()) }

    @JvmStatic
    fun clearPendingAutocorrection() {
        synchronized(core) { core.clearPendingAutocorrection() }
    }

    /** The user undid or deleted an autocorrection of [typed]. */
    @JvmStatic
    fun onAutocorrectRejected(typed: String) {
        val ctx = appContext ?: return
        load(ctx)

        val result = synchronized(core) {
            core.recordRejection(typed, System.currentTimeMillis())?.also { _words.value = core.all() }
        } ?: return

        Log.d(TAG, "Kept \"${result.entry.word}\" (${result.entry.rejections}x)")
        save(ctx)
        if (result.shouldSaveToDictionary) addToPersonalDictionary(ctx, result.entry.word)
    }

    /** Forgets a word. It is not removed from the personal dictionary, which the user manages. */
    fun remove(word: String) {
        val ctx = appContext
        synchronized(core) {
            core.remove(word)
            _words.value = core.all()
        }
        ctx?.let { save(it) }
    }

    fun clear() {
        val ctx = appContext
        synchronized(core) {
            core.clear()
            _words.value = core.all()
        }
        ctx?.let { save(it) }
    }

    private fun save(context: Context) {
        // Until the saved list is loaded, saving would overwrite it with a partial list.
        // load() saves once it has merged.
        if (!loaded) return
        scope.launch {
            if (!context.isDirectBootUnlocked) return@launch
            val json = synchronized(core) { core.toJson() }
            runCatching {
                val target = file(context)
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeText(json)
                if (!temp.renameTo(target)) {
                    target.writeText(json)
                    temp.delete()
                }
            }.onFailure { Log.w(TAG, "Could not save learned words", it) }
        }
    }

    /** Adds the word for all languages, unless it is already there in any capitalization. */
    private fun addToPersonalDictionary(context: Context, word: String) {
        scope.launch {
            if (!context.isDirectBootUnlocked) return@launch
            try {
                val exists = context.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(UserDictionary.Words.WORD),
                    "${UserDictionary.Words.WORD} = ? COLLATE NOCASE",
                    arrayOf(word),
                    null
                )?.use { it.count > 0 } ?: false

                if (!exists) {
                    UserDictionaryCompatUtils.addWord(context, word, PERSONAL_DICTIONARY_FREQUENCY, null, null)
                    Log.d(TAG, "Added \"$word\" to the personal dictionary")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not add \"$word\" to the personal dictionary", e)
            }
        }
    }
}
