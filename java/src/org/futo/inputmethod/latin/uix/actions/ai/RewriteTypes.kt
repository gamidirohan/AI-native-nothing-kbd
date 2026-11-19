package org.futo.inputmethod.latin.uix.actions.ai

import androidx.annotation.StringRes
import org.futo.inputmethod.latin.R

enum class RewriteCategory(@StringRes val label: Int) {
    Editor(R.string.ai_write_tab_editor),
    Tone(R.string.ai_write_tab_tone)
}

private const val SPELLING_STEP = "Fix the spelling mistakes in the text. Keep everything else the same."
private const val GRAMMAR_STEP = "Fix the grammar mistakes in the text. Keep the meaning."

// The example matters: without it the small model often returns the text unchanged.
private const val PUNCTUATION_STEP =
    "Add the missing punctuation, apostrophes and capital letters to the text. Keep the same words." +
            "\n\nExample:\nText: dont be late its important\nResult: Don't be late. It's important."

/**
 * Every rewrite option the user can pick. [instructions] are run one after another, each on the
 * previous result. The on-device model is small, so it does best with one short, narrow
 * instruction at a time; "Fix all" is therefore spelling, then grammar, then punctuation.
 * These wordings were tried against the real Qwen3 0.6B model.
 */
enum class RewriteStyle(
    val category: RewriteCategory,
    @StringRes val label: Int,
    val instructions: List<String>
) {
    // Editor
    FixAll(
        RewriteCategory.Editor, R.string.ai_write_fix_all,
        listOf(SPELLING_STEP, GRAMMAR_STEP, PUNCTUATION_STEP)
    ),
    Grammar(RewriteCategory.Editor, R.string.ai_write_grammar, listOf(GRAMMAR_STEP)),
    Spelling(RewriteCategory.Editor, R.string.ai_write_spelling, listOf(SPELLING_STEP)),
    Punctuation(RewriteCategory.Editor, R.string.ai_write_punctuation, listOf(PUNCTUATION_STEP)),

    // Tone
    Professional(
        RewriteCategory.Tone, R.string.ai_write_professional,
        listOf("Rewrite the text in a professional, businesslike tone. Keep the meaning.")
    ),
    Casual(
        RewriteCategory.Tone, R.string.ai_write_casual,
        listOf("Rewrite the text in a relaxed, casual, conversational tone. Keep the meaning.")
    ),
    Polite(
        RewriteCategory.Tone, R.string.ai_write_polite,
        listOf("Rewrite the text so it is polite, courteous and considerate. Keep the meaning.")
    ),
    Funny(
        RewriteCategory.Tone, R.string.ai_write_funny,
        listOf("Rewrite the text so it is light-hearted and funny, with a little humor. Keep the meaning.")
    ),
    LinkedIn(
        RewriteCategory.Tone, R.string.ai_write_linkedin,
        listOf("Rewrite the text as a LinkedIn-style message: professional, positive and engaging. Keep the meaning.")
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

    fun user(instruction: String, text: String): String = "$instruction\n\nText:\n$text"
}
