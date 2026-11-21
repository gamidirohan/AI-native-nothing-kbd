package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import org.futo.inputmethod.latin.uix.getSettingBlocking
import kotlinx.coroutines.delay

/**
 * Backend that turns a [RewriteRequest] into rewritten text. Callers invoke [rewrite] from a
 * coroutine on the main thread and may cancel it, so implementations must move blocking
 * inference onto their own dispatcher.
 */
interface RewriteEngine {
    val name: String

    /**
     * Rewrites the request text. [onPartial] is called with the full text generated so far
     * whenever more output is available, so the UI can stream it. Returns the final text.
     * Throws [RewriteException] if the engine cannot produce a result.
     */
    suspend fun rewrite(request: RewriteRequest, onPartial: (String) -> Unit): String
}

open class RewriteException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The model file has not been downloaded or imported yet. */
    class ModelMissing : RewriteException("AI model not installed yet.")
}

/**
 * Placeholder engine used until a real on-device model is wired in. It returns the input
 * prefixed with the chosen style so the UI flow (read selection, preview, replace, copy) can be
 * exercised end to end.
 */
class StubRewriteEngine : RewriteEngine {
    override val name = "Stub"

    override suspend fun rewrite(request: RewriteRequest, onPartial: (String) -> Unit): String {
        val result = "[${request.style.name}] ${request.text}"
        val builder = StringBuilder()
        for (word in result.split(" ")) {
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(word)
            onPartial(builder.toString())
            delay(40)
        }
        return result
    }
}

object RewriteEngineProvider {
    @Volatile
    private var engine: RewriteEngine? = null

    fun get(context: Context): RewriteEngine {
        return engine ?: synchronized(this) {
            engine ?: LiteRtRewriteEngine(context).also { engine = it }
        }
    }

    /** Frees the model's memory if it is loaded. Safe to call at any time. */
    fun release() {
        (engine as? LiteRtRewriteEngine)?.release()
    }

    /** Android asked the keyboard to use less memory: free the model unless it is kept loaded. */
    fun onMemoryTrim(context: Context) {
        if (!context.getSettingBlocking(AI_KEEP_LOADED)) release()
    }

    /** Loads the model in the background if it is kept loaded and installed. Cheap if loaded. */
    @JvmStatic
    fun preloadIfKept(context: Context) {
        val appContext = context.applicationContext
        if (!LlmModelStore.isInstalled(appContext)) return
        if (!appContext.getSettingBlocking(AI_KEEP_LOADED)) return
        (get(appContext) as? LiteRtRewriteEngine)?.preload()
    }
}
