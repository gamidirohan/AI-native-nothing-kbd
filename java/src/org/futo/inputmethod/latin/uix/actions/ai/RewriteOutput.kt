package org.futo.inputmethod.latin.uix.actions.ai

/**
 * Small models often wrap answers in quotes, add a "Sure, here is..." line, or (Qwen3) emit a
 * `<think>` block first. This strips those so only the rewritten text is shown and inserted.
 * It is safe to call on partial streamed text.
 *
 * Stripping is skipped when the user's own text starts the same way, so a message that really
 * begins with "Here are the notes:" or is wrapped in quotes keeps that.
 */
object RewriteOutput {
    private val thinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val preamble = Regex(
        "^(sure|certainly|of course|okay|ok|here(?:'s| is| are)|rewritten|corrected|result)\\b[^\\n]*:\\s*\\n+",
        RegexOption.IGNORE_CASE
    )

    private val quotePairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '`' to '`')

    fun clean(raw: String, input: String): String {
        var text = raw.replace(thinkBlock, "")

        // A think block that has started but not finished: nothing to show yet.
        val openThink = text.indexOf("<think>")
        if (openThink >= 0) text = text.substring(0, openThink)

        text = text.trim()
        val original = input.trim()

        val lead = preamble.find(text)
        if (lead != null) {
            val firstWord = lead.groupValues[1]
            if (!original.startsWith(firstWord, ignoreCase = true)) {
                text = text.substring(lead.range.last + 1).trim()
            }
        }

        for ((open, close) in quotePairs) {
            val wrapped = text.length >= 2 && text.first() == open && text.last() == close
            val inputWasWrapped = original.isNotEmpty() && original.first() == open
            if (wrapped && !inputWasWrapped && text.count { it == open } <= (if (open == close) 2 else 1)) {
                text = text.substring(1, text.length - 1).trim()
                break
            }
        }
        return text
    }
}
