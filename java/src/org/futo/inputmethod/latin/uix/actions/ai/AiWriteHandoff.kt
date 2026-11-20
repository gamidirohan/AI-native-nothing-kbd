package org.futo.inputmethod.latin.uix.actions.ai

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

/**
 * Passes text from another part of the keyboard (the clipboard history) into the AI Writing
 * panel, which is created fresh each time it opens.
 */
object AiWriteHandoff {
    @Volatile
    private var pendingClipboardText: String? = null

    /** Text of the last clipboard item that was translated automatically. */
    @Volatile
    private var lastAutoHandled: String? = null

    fun offerClipboardText(text: String) {
        pendingClipboardText = text
    }

    /** Returns the offered text once. */
    fun takePendingClipboardText(): String? {
        val text = pendingClipboardText
        pendingClipboardText = null
        return text
    }

    fun markAutoHandled(text: String) {
        lastAutoHandled = text
    }

    fun isNewForAutoTranslate(text: String): Boolean = text != lastAutoHandled
}

/** Reads the current clipboard text, skipping items the source app marked as sensitive. */
fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip: ClipData = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null

    val isSensitive = clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
    if (isSensitive) return null

    val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
    return text?.takeIf { it.isNotBlank() }
}
