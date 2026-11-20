package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

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

    /** Every LiteRT-LM model file starts with these 8 bytes. */
    private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

    fun directory(context: Context): File =
        File(context.filesDir, "llm-models").also { it.mkdirs() }

    /** The file the engine loads. Downloaded and imported models both end up here. */
    fun defaultModelFile(context: Context): File = File(directory(context), DEFAULT_MODEL.fileName)

    /** The in-progress download. Renamed to [defaultModelFile] only after the hash matches. */
    fun partialFile(context: Context): File = File(directory(context), DEFAULT_MODEL.fileName + ".part")

    fun isInstalled(context: Context): Boolean =
        defaultModelFile(context).let { it.isFile && it.length() > 0 }

    /** Size of the installed model in bytes, or 0 if there is none. */
    fun installedSizeBytes(context: Context): Long =
        defaultModelFile(context).let { if (it.isFile) it.length() else 0L }

    fun delete(context: Context) {
        // The engine keeps the file open while the model is loaded, so release it first.
        RewriteEngineProvider.release()
        defaultModelFile(context).delete()
        partialFile(context).delete()
    }

    /**
     * Copies a model file chosen by the user into the store. Blocking; call it off the main
     * thread. Replaces any installed model.
     *
     * Only the file header is checked, so this rejects the wrong kind of file (such as a GGUF)
     * but cannot tell a damaged LiteRT-LM file from a good one.
     */
    fun importFrom(context: Context, uri: Uri) {
        val resolver = context.contentResolver

        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

        val dir = directory(context)
        if (declaredSize != null && dir.usableSpace < declaredSize + SPACE_MARGIN_BYTES) {
            throw IOException("Not enough free storage for this file.")
        }

        val partial = File(dir, "import.part")
        partial.delete()

        try {
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(MAGIC.size)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < header.size || !header.contentEquals(MAGIC)) {
                    throw IOException("This is not a LiteRT-LM model file. It must be a .litertlm file.")
                }

                partial.outputStream().use { out ->
                    out.write(header)
                    input.copyTo(out)
                }
            } ?: throw IOException("Could not open the file.")

            if (partial.length() <= MAGIC.size) throw IOException("The file is empty.")

            RewriteEngineProvider.release()
            val target = defaultModelFile(context)
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not save the model file.")
        } finally {
            partial.delete()
        }
    }

    const val SPACE_MARGIN_BYTES = 200L * 1024L * 1024L
}
