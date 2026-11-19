package org.futo.inputmethod.latin.uix.actions.ai

import androidx.annotation.StringRes
import org.futo.inputmethod.latin.R

enum class RewriteCategory(@StringRes val label: Int) {
    Editor(R.string.ai_write_tab_editor),
    Tone(R.string.ai_write_tab_tone)
}

/**
 * Every rewrite option the user can pick. [instruction] is the task description given to the
 * model; it is deliberately short and explicit because the on-device models are small.
 */
enum class RewriteStyle(
    val category: RewriteCategory,
    @StringRes val label: Int,
    val instruction: String
) {
    // Editor
    FixAll(
        RewriteCategory.Editor, R.string.ai_write_fix_all,
        "Fix all grammar, spelling and punctuation mistakes. Do not change the wording, meaning or tone otherwise."
    ),
    Grammar(
        RewriteCategory.Editor, R.string.ai_write_grammar,
        "Fix only the grammar mistakes. Do not change spelling, punctuation, wording or tone otherwise."
    ),
    Spelling(
        RewriteCategory.Editor, R.string.ai_write_spelling,
        "Fix only the spelling mistakes. Do not change grammar, punctuation, wording or tone otherwise."
    ),
    Punctuation(
        RewriteCategory.Editor, R.string.ai_write_punctuation,
        "Fix only the punctuation and capitalization. Do not change any words."
    ),

    // Tone
    Professional(
        RewriteCategory.Tone, R.string.ai_write_professional,
        "Rewrite the text in a professional, businesslike tone. Keep the meaning."
    ),
    Casual(
        RewriteCategory.Tone, R.string.ai_write_casual,
        "Rewrite the text in a relaxed, casual, conversational tone. Keep the meaning."
    ),
    Polite(
        RewriteCategory.Tone, R.string.ai_write_polite,
        "Rewrite the text so it is polite, courteous and considerate. Keep the meaning."
    ),
    Funny(
        RewriteCategory.Tone, R.string.ai_write_funny,
        "Rewrite the text so it is light-hearted and funny, with a little humor. Keep the meaning."
    ),
    LinkedIn(
        RewriteCategory.Tone, R.string.ai_write_linkedin,
        "Rewrite the text as a LinkedIn-style message: professional, positive and engaging. Keep the meaning."
    );

    companion object {
        fun forCategory(category: RewriteCategory) = entries.filter { it.category == category }
    }
}

data class RewriteRequest(
    val style: RewriteStyle,
    val text: String
)

/** Builds the prompts handed to the model. Kept separate from any specific engine. */
object RewritePrompts {
    const val SYSTEM = "You are a writing assistant built into a phone keyboard. " +
            "You receive a short message and one instruction. " +
            "Reply with only the resulting text. " +
            "Do not add explanations, quotes, notes or a preamble. " +
            "Keep the language of the original text."

    fun user(request: RewriteRequest): String =
        "${request.style.instruction}\n\nText:\n${request.text}"
}
