package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import java.io.File

/**
 * Where the rewrite model lives on disk. This is kept apart from `transformer-models/`, which
 * holds the keyboard's own next-word model and rejects anything that is not a KeyboardLM.
 *
 * Download and import are added in a later step; for now the file just has to exist here.
 */
object LlmModelStore {
    const val DEFAULT_MODEL_FILE = "Qwen3-0.6B.litertlm"

    fun directory(context: Context): File =
        File(context.filesDir, "llm-models").also { it.mkdirs() }

    fun defaultModelFile(context: Context): File = File(directory(context), DEFAULT_MODEL_FILE)

    fun isInstalled(context: Context): Boolean =
        defaultModelFile(context).let { it.isFile && it.length() > 0 }
}
