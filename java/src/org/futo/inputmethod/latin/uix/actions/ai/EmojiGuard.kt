package org.futo.inputmethod.latin.uix.actions.ai

/**
 * Keeps emoji, and any other character outside Unicode's Basic Multilingual Plane, away from the
 * on-device model.
 *
 * Why: Java passes strings to native code as "modified UTF-8", which encodes these characters
 * (most emoji, such as 😊 🎉 👍) as two separate halves. The LiteRT-LM tokenizer does not handle
 * that: on a desktop test the model hung on every input containing one, and one run crashed the
 * process. Emoji inside the BMP (❤ ☺ ✅) were fine. See docs/AI_WRITING_ASSISTANT.md.
 *
 * The model also cannot be trusted to copy a placeholder through (it kept 0-3 of 5 in tests), so
 * emoji are removed before the text goes in and put back afterwards:
 * - emoji at the start or end of the text stay at the start or end;
 * - emoji in the middle go back after the same word when the wording is kept (Fix all), and at
 *   the end otherwise (tone rewrites, translation), since word positions no longer line up.
 */
object EmojiGuard {
    /** An emoji group found in the text, and how many words came before it. */
    data class Cluster(val text: String, val wordsBefore: Int)

    data class Stripped(
        /** The text with emoji removed and the spaces around them tidied up. */
        val text: String,
        val leading: String,
        val trailing: String,
        val middle: List<Cluster>
    ) {
        val hasEmoji: Boolean get() = leading.isNotEmpty() || trailing.isNotEmpty() || middle.isNotEmpty()
    }

    // Characters that belong to an emoji group when next to one: zero-width joiner, variation
    // selectors and the combining keycap.
    private fun isJoiner(codePoint: Int) =
        codePoint == 0x200D || codePoint == 0xFE0E || codePoint == 0xFE0F || codePoint == 0x20E3

    private fun isProtected(codePoint: Int) = codePoint > 0xFFFF

    /** True if [text] contains anything the model must not see. */
    fun needsGuard(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isProtected(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    fun strip(text: String): Stripped {
        // Split into pieces: plain text and emoji groups.
        data class Piece(val text: String, val isEmoji: Boolean)
        val pieces = mutableListOf<Piece>()
        val current = StringBuilder()
        var currentIsEmoji = false

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = String(Character.toChars(cp))
            val emoji = isProtected(cp) || (isJoiner(cp) && currentIsEmoji)
            if (current.isNotEmpty() && emoji != currentIsEmoji) {
                pieces.add(Piece(current.toString(), currentIsEmoji))
                current.setLength(0)
            }
            currentIsEmoji = emoji
            current.append(chars)
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) pieces.add(Piece(current.toString(), currentIsEmoji))

        val firstText = pieces.indexOfFirst { !it.isEmoji && it.text.isNotBlank() }
        val lastText = pieces.indexOfLast { !it.isEmoji && it.text.isNotBlank() }
        if (firstText < 0) {
            // Only emoji and spaces: nothing for the model to work on.
            return Stripped("", text, "", emptyList())
        }

        val leading = pieces.subList(0, firstText).joinToString("") { it.text }.trimEnd()
        val trailing = pieces.subList(lastText + 1, pieces.size).joinToString("") { it.text }.trimStart()

        val body = StringBuilder()
        val middle = mutableListOf<Cluster>()
        for (index in firstText..lastText) {
            val piece = pieces[index]
            if (piece.isEmoji) {
                middle.add(Cluster(piece.text, countWords(body.toString())))
            } else {
                body.append(piece.text)
            }
        }

        val cleaned = body.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex(" +([,.!?;:])"), "$1")
            .trim()
        return Stripped(cleaned, leading, trailing, middle)
    }

    /**
     * Puts the emoji from [stripped] back into the model's [output]. With [keepPositions], middle
     * emoji go back after the same number of words; otherwise they go at the end.
     */
    fun restore(output: String, stripped: Stripped, keepPositions: Boolean): String {
        if (!stripped.hasEmoji) return output

        var body = output.trim()
        if (stripped.middle.isNotEmpty()) {
            body = if (keepPositions) {
                insertAfterWords(body, stripped.middle)
            } else {
                joinWithSpace(body, stripped.middle.joinToString(" ") { it.text })
            }
        }

        var result = joinWithSpace(stripped.leading, body)
        result = joinWithSpace(result, stripped.trailing)
        return result
    }

    private fun insertAfterWords(body: String, clusters: List<Cluster>): String {
        val words = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // Insert from the last position backwards so earlier indexes stay valid.
        for (cluster in clusters.sortedByDescending { it.wordsBefore }) {
            val at = cluster.wordsBefore.coerceIn(0, words.size)
            words.add(at, cluster.text)
        }
        return words.joinToString(" ")
    }

    private fun joinWithSpace(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a $b"
    }

    private fun countWords(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotEmpty() }
}
