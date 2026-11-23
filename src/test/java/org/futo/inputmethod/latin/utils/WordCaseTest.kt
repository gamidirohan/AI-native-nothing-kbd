package org.futo.inputmethod.latin.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WordCaseTest {
    private val en = Locale.ENGLISH

    @Test fun `cycles title, all caps, lowercase`() {
        assertEquals("Hello", WordCase.next("hello", en))
        assertEquals("HELLO", WordCase.next("Hello", en))
        assertEquals("hello", WordCase.next("HELLO", en))
    }

    @Test fun `mixed case goes to title`() {
        assertEquals("Iphone", WordCase.next("iPhone", en))
    }

    @Test fun `single letter toggles`() {
        assertEquals("I", WordCase.next("i", en))
        assertEquals("i", WordCase.next("I", en))
    }

    @Test fun `apostrophes and non-letters are kept`() {
        assertEquals("Don't", WordCase.next("don't", en))
        assertEquals("DON'T", WordCase.next("Don't", en))
        assertEquals("123", WordCase.next("123", en))
    }

    @Test fun `non latin scripts without case are unchanged`() {
        assertEquals("నేను", WordCase.next("నేను", en))
    }
}
