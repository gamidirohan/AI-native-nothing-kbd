package org.futo.inputmethod.latin.uix.actions.ai

import androidx.annotation.StringRes
import org.futo.inputmethod.latin.R

enum class RewriteCategory(@StringRes val label: Int) {
    Editor(R.string.ai_write_tab_editor),
    Tone(R.string.ai_write_tab_tone)
}

/**
 * How far the model may go from the original text, like an "accuracy level". The setting is
 * stored by [ordinal], so do not reorder these.
 */
enum class FormatLevel(@StringRes val label: Int) {
    Light(R.string.ai_write_level_light),
    Balanced(R.string.ai_write_level_balanced),
    Strong(R.string.ai_write_level_strong);

    /** Extra wording appended to the instruction, or null to leave the instruction alone. */
    fun suffix(category: RewriteCategory): String? = when (this) {
        Light -> if (category == RewriteCategory.Tone) {
            " Change as little as possible. Keep the original wording and sentence structure."
        } else null
        Balanced -> null
        Strong -> if (category == RewriteCategory.Tone) {
            " You may restructure sentences and change the wording freely."
        } else {
            " You may also improve unclear wording."
        }
    }

    /** Editing should stay close to the input; tone changes may be freer. */
    fun temperature(category: RewriteCategory): Double = when (category) {
        RewriteCategory.Editor -> when (this) {
            Light -> 0.1
            Balanced -> 0.2
            Strong -> 0.4
        }
        RewriteCategory.Tone -> when (this) {
            Light -> 0.4
            Balanced -> 0.7
            Strong -> 0.9
        }
    }

    companion object {
        fun fromOrdinal(value: Int): FormatLevel = entries.getOrElse(value) { Balanced }
    }
}

private const val SPELLING_STEP = "Fix the spelling mistakes in the text. Keep everything else the same."
private const val GRAMMAR_STEP = "Fix the grammar mistakes in the text. Keep the meaning."

// The example matters: without it the small model often returns the text unchanged.
private const val PUNCTUATION_STEP =
    "Add the missing punctuation, apostrophes and capital letters to the text. Keep the same words." +
            "\n\nExample:\nText: dont be late its important\nResult: Don't be late. It's important."

/** Replaced with the target language name when the instruction is built. */
const val LANGUAGE_PLACEHOLDER = "{language}"

/**
 * Every rewrite option the user can pick. [instructions] are run one after another, each on the
 * previous result. The on-device model is small, so it does best with one short, narrow
 * instruction at a time; "Fix all" is therefore spelling, then grammar, then punctuation.
 * These wordings were tried against the real Qwen3 0.6B model.
 */
enum class RewriteStyle(
    val category: RewriteCategory,
    @StringRes val label: Int,
    val instructions: List<String>,
    /** True if the user has to choose a target language (the instruction uses [LANGUAGE_PLACEHOLDER]). */
    val needsLanguage: Boolean = false
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
    ),
    Shorten(
        RewriteCategory.Tone, R.string.ai_write_shorten,
        listOf("Rewrite the text to be shorter. Keep the key meaning and the tone.")
    ),
    Translate(
        RewriteCategory.Tone, R.string.ai_write_translate,
        listOf("Translate the text into $LANGUAGE_PLACEHOLDER. Keep the meaning and the tone."),
        needsLanguage = true
    );

    companion object {
        fun forCategory(category: RewriteCategory) = entries.filter { it.category == category }
    }
}

data class RewriteRequest(
    val style: RewriteStyle,
    val text: String,
    val level: FormatLevel = FormatLevel.Balanced,
    /** Display name of the target language, e.g. "Spanish". Only used by styles with [RewriteStyle.needsLanguage]. */
    val targetLanguage: String? = null
)

/** Builds the prompts handed to the model. Kept separate from any specific engine. */
object RewritePrompts {
    private const val SYSTEM_REWRITE = "You are a writing assistant built into a phone keyboard. " +
            "You receive a short message and one instruction. " +
            "Reply with only the resulting text. " +
            "Do not add explanations, quotes, notes or a preamble. " +
            "Keep the language of the original text."

    // Must not say "keep the language", or the model would refuse to translate.
    private const val SYSTEM_TRANSLATE = "You are a translator built into a phone keyboard. " +
            "You receive a short message and the language to translate it into. " +
            "Reply with only the translation. " +
            "Do not add explanations, quotes, notes or a preamble."

    fun system(style: RewriteStyle): String =
        if (style.needsLanguage) SYSTEM_TRANSLATE else SYSTEM_REWRITE

    /** The instruction for one step, with the formatting level and target language applied. */
    fun instruction(request: RewriteRequest, step: String): String {
        var result = step.replace(LANGUAGE_PLACEHOLDER, request.targetLanguage ?: "English")

        // Tone styles are a single step, so the level applies to it. For Editor styles only the
        // grammar step takes it: "improve unclear wording" makes no sense for spelling or
        // punctuation, which must not change the words.
        val levelApplies = request.style.category == RewriteCategory.Tone || step == GRAMMAR_STEP
        if (levelApplies) {
            request.level.suffix(request.style.category)?.let { result = insertSuffix(result, it) }
        }
        return result
    }

    // A suffix has to come before any "Example:" block, otherwise the model sees it as part of
    // the example.
    private fun insertSuffix(instruction: String, suffix: String): String {
        val exampleAt = instruction.indexOf("\n\nExample:")
        return if (exampleAt >= 0) {
            instruction.substring(0, exampleAt) + suffix + instruction.substring(exampleAt)
        } else {
            instruction + suffix
        }
    }

    fun user(instruction: String, text: String): String = "$instruction\n\nText:\n$text"
}
