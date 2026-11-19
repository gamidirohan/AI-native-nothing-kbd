package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import java.io.File

/** A model file the rewrite engine can download. The hash is pinned so a bad file is rejected. */
data class LlmModelSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val displayName: String
)

/**
 * Where the rewrite model lives on disk. This is kept apart from `transformer-models/`, which
 * holds the keyboard's own next-word model and rejects anything that is not a KeyboardLM.
 */
object LlmModelStore {
    /** Qwen3 0.6B (Apache-2.0) from the litert-community Hugging Face organisation. */
    val DEFAULT_MODEL = LlmModelSpec(
        fileName = "Qwen3-0.6B.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        sizeBytes = 613_722_264L,
        sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
        displayName = "Qwen3 0.6B"
    )

    fun directory(context: Context): File =
        File(context.filesDir, "llm-models").also { it.mkdirs() }

    fun defaultModelFile(context: Context): File = File(directory(context), DEFAULT_MODEL.fileName)

    /** The in-progress download. Renamed to [defaultModelFile] only after the hash matches. */
    fun partialFile(context: Context): File = File(directory(context), DEFAULT_MODEL.fileName + ".part")

    fun isInstalled(context: Context): Boolean =
        defaultModelFile(context).let { it.isFile && it.length() > 0 }

    fun delete(context: Context) {
        defaultModelFile(context).delete()
        partialFile(context).delete()
    }
}
