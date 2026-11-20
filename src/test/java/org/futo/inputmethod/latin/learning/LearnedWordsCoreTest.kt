package org.futo.inputmethod.latin.learning

import org.junit.Assert.*
import org.junit.Test

class LearnedWordsCoreTest {
    @Test fun `new word is not kept`() {
        assertFalse(LearnedWordsCore().isKept("vastanu"))
    }

    @Test fun `one rejection stops autocorrect, case insensitive`() {
        val c = LearnedWordsCore()
        val r = c.recordRejection("vastanu", 1000)!!
        assertEquals(1, r.entry.rejections)
        assertFalse(r.shouldSaveToDictionary)
        assertTrue(c.isKept("vastanu"))
        assertTrue(c.isKept("Vastanu"))
        assertTrue(c.isKept("VASTANU"))
        assertFalse(c.isKept("vastan"))
    }

    @Test fun `second rejection asks to save to dictionary exactly once`() {
        val c = LearnedWordsCore()
        assertFalse(c.recordRejection("kya", 1)!!.shouldSaveToDictionary)
        assertTrue(c.recordRejection("Kya", 2)!!.shouldSaveToDictionary)
        assertFalse(c.recordRejection("kya", 3)!!.shouldSaveToDictionary)
        assertEquals(3, c.get("KYA")!!.rejections)
        assertEquals("kya", c.get("kya")!!.word)  // latest capitalization
    }

    @Test fun `non words are ignored`() {
        val c = LearnedWordsCore()
        for (w in listOf("a", "", " ", "abc123", "www.x.com", "hi there", "1990", "ok!", "-ab", "ab-", "'ab")) {
            assertNull("should ignore '$w'", c.recordRejection(w, 1))
        }
        assertNotNull(c.recordRejection("don't", 1))
        assertNotNull(c.recordRejection("ek-do", 1))
        assertNotNull(c.recordRejection("  panra  ", 1))
        assertTrue(c.isKept("panra"))
        assertNotNull(c.recordRejection("నేను", 1))   // Telugu, ends in a vowel sign
        assertNotNull(c.recordRejection("नमस्ते", 1))  // Hindi, with a virama
        assertNotNull(c.recordRejection("என்ன", 1))    // Tamil
        assertNull(c.recordRejection("\u0947ab", 1))   // cannot start with a vowel sign
    }

    @Test fun `deleting the latest autocorrection within the window rejects it`() {
        val c = LearnedWordsCore()
        c.onAutocorrected("enna", "Emma", 10_000)
        assertNull("other word", c.onWordDeleted("hello", 11_000))
        assertEquals("enna", c.onWordDeleted("Emma", 12_000))
        assertNull("used up", c.onWordDeleted("Emma", 13_000))
    }

    @Test fun `deleting after the window does nothing`() {
        val c = LearnedWordsCore()
        c.onAutocorrected("enna", "Emma", 0)
        assertNull(c.onWordDeleted("Emma", LearnedWordsCore.DELETE_WINDOW_MILLIS + 1))
    }

    @Test fun `only the most recent autocorrection is tracked`() {
        val c = LearnedWordsCore()
        c.onAutocorrected("nenu", "menu", 0)
        c.onAutocorrected("enna", "Emma", 100)
        assertNull(c.onWordDeleted("menu", 200))
        assertEquals("enna", c.onWordDeleted("Emma", 300))
    }

    @Test fun `clearing pending stops a double count after an undo`() {
        val c = LearnedWordsCore()
        c.onAutocorrected("enna", "Emma", 0)
        c.clearPendingAutocorrection()
        assertNull(c.onWordDeleted("Emma", 10))
    }

    @Test fun `json round trip and merge adds counts`() {
        val a = LearnedWordsCore()
        a.recordRejection("vastanu", 100)
        a.recordRejection("vastanu", 200)
        a.recordRejection("kya", 300)
        val json = a.toJson()

        val b = LearnedWordsCore()
        b.recordRejection("Vastanu", 400)   // recorded before the saved list loaded
        b.mergeJson(json)
        assertEquals(3, b.get("vastanu")!!.rejections)
        assertEquals("Vastanu", b.get("vastanu")!!.word)
        assertEquals(400, b.get("vastanu")!!.lastRejectedMillis)
        assertEquals(1, b.get("kya")!!.rejections)
        assertEquals(listOf("Vastanu", "kya"), b.all().map { it.word })
    }

    @Test fun `bad json is ignored`() {
        val c = LearnedWordsCore()
        c.recordRejection("kya", 1)
        c.mergeJson("not json")
        c.mergeJson("[{\"word\":\"1x\",\"rejections\":3,\"lastRejectedMillis\":1}]")
        assertEquals(listOf("kya"), c.all().map { it.word })
    }

    @Test fun `list is capped, oldest dropped`() {
        val c = LearnedWordsCore()
        for (i in 0 until LearnedWordsCore.MAX_WORDS + 20) {
            c.recordRejection("word" + ('a' + i % 26) + ('a' + i / 26 % 26) + ('a' + i / 676), i.toLong())
        }
        assertEquals(LearnedWordsCore.MAX_WORDS, c.all().size)
        assertFalse(c.isKept("wordaaa"))       // i = 0, oldest
        assertTrue(c.isKept(c.all().first().word))
    }

    @Test fun `remove and clear`() {
        val c = LearnedWordsCore()
        c.recordRejection("kya", 1)
        assertTrue(c.remove("KYA"))
        assertFalse(c.isKept("kya"))
        c.recordRejection("nenu", 2)
        c.onAutocorrected("x", "y", 3)
        c.clear()
        assertTrue(c.all().isEmpty())
        assertNull(c.onWordDeleted("y", 4))
    }
}
