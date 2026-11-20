package org.futo.inputmethod.latin.uix.actions.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiGuardTest {
    private fun noSupplementary(s: String) = !EmojiGuard.needsGuard(s)

    @Test fun `text without emoji is unchanged`() {
        val s = EmojiGuard.strip("hey can u send it")
        assertEquals("hey can u send it", s.text)
        assertFalse(s.hasEmoji)
        assertEquals("Hey, can you send it?", EmojiGuard.restore("Hey, can you send it?", s, true))
    }

    @Test fun `bmp symbols are left in the text`() {
        // ❤ ☺ ✅ are in the BMP and were handled fine by the model.
        assertFalse(EmojiGuard.needsGuard("i ❤ this ☺ ✅"))
        assertEquals("i ❤ this ☺ ✅", EmojiGuard.strip("i ❤ this ☺ ✅").text)
    }

    @Test fun `trailing emoji stays at the end`() {
        val s = EmojiGuard.strip("happy birthday hope u have a grate day 🎉🎂")
        assertEquals("happy birthday hope u have a grate day", s.text)
        assertTrue(noSupplementary(s.text))
        assertEquals("Happy birthday, hope you have a great day! 🎉🎂",
            EmojiGuard.restore("Happy birthday, hope you have a great day!", s, true))
    }

    @Test fun `leading emoji stays at the start`() {
        val s = EmojiGuard.strip("😂 lol thats so funny")
        assertEquals("lol thats so funny", s.text)
        assertEquals("😂 Lol, that's so funny.", EmojiGuard.restore("Lol, that's so funny.", s, true))
    }

    @Test fun `middle emoji goes back after the same word for fix all`() {
        val s = EmojiGuard.strip("hi 😊 how r u")
        assertEquals("hi how r u", s.text)
        assertEquals("Hi. 😊 How are you?", EmojiGuard.restore("Hi. How are you?", s, true))
    }

    @Test fun `middle emoji goes to the end for rewrites`() {
        val s = EmojiGuard.strip("thanks 🙏 see you tomorrow")
        assertEquals("Thank you, I will see you tomorrow. 🙏",
            EmojiGuard.restore("Thank you, I will see you tomorrow.", s, false))
    }

    @Test fun `several middle emoji keep their order`() {
        val s = EmojiGuard.strip("a 👍 b c 🔥 d")
        assertEquals("a b c d", s.text)
        assertEquals("A 👍 b c 🔥 d.", EmojiGuard.restore("A b c d.", s, true))
        assertEquals("A b c d. 👍 🔥", EmojiGuard.restore("A b c d.", s, false))
    }

    @Test fun `emoji before punctuation leaves no stray space`() {
        val s = EmojiGuard.strip("great job 👏, see you")
        assertEquals("great job, see you", s.text)
    }

    @Test fun `zwj sequences, skin tones and flags stay whole`() {
        val family = "👨‍👩‍👧"
        val thumbs = "👍🏽"
        val flag = "🇮🇳"
        val s = EmojiGuard.strip("$family nice $thumbs ok $flag")
        assertEquals("nice ok", s.text)
        assertTrue(noSupplementary(s.text))
        assertFalse(s.text.contains('\u200D'))
        assertEquals("$family Nice $thumbs ok. $flag", EmojiGuard.restore("Nice ok.", s, true))
    }

    @Test fun `only emoji means nothing for the model`() {
        val s = EmojiGuard.strip(" 👍 ")
        assertEquals("", s.text)
        assertTrue(s.hasEmoji)
    }

    @Test fun `more middle emoji than words in the output are put at the end`() {
        val s = EmojiGuard.strip("one two three 😀 four")
        assertEquals("Hi 😀", EmojiGuard.restore("Hi", s, true))
    }
}
