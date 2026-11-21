package org.futo.inputmethod.latin.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedCorrectionsTest {
    @Test fun `finger slips and small edits are typos, rewording is not`() {
        assertTrue(LearnedWordsCore.isPlausibleTypo("kr", "it"))        // each letter one key off
        assertTrue(LearnedWordsCore.isPlausibleTypo("whys", "why's"))   // apostrophe
        assertTrue(LearnedWordsCore.isPlausibleTypo("teh", "the"))      // swapped letters
        assertTrue(LearnedWordsCore.isPlausibleTypo("tommorow", "tomorrow"))
        assertFalse(LearnedWordsCore.isPlausibleTypo("car", "bus"))
        assertFalse(LearnedWordsCore.isPlausibleTypo("hello", "goodbye"))
        assertFalse(LearnedWordsCore.isPlausibleTypo("the", "THE"))     // same word
    }

    @Test fun `nearby keys`() {
        assertTrue(LearnedWordsCore.isNearbyKey('k', 'i'))
        assertTrue(LearnedWordsCore.isNearbyKey('r', 't'))
        assertTrue(LearnedWordsCore.isNearbyKey('s', 'z'))
        assertFalse(LearnedWordsCore.isNearbyKey('q', 'p'))
        assertFalse(LearnedWordsCore.isNearbyKey('a', 'l'))
    }

    @Test fun `correction applies after the second fix`() {
        val c = LearnedWordsCore()
        assertEquals(1, c.recordReplacement("kr", "it", 1)!!.count)
        assertNull(c.correctionFor("kr"))
        assertEquals(2, c.recordReplacement("kr", "it", 2)!!.count)
        assertEquals("it", c.correctionFor("kr"))
    }

    @Test fun `capitalization follows what was typed`() {
        val c = LearnedWordsCore()
        c.recordReplacement("whys", "why's", 1)
        c.recordReplacement("whys", "why's", 2)
        assertEquals("why's", c.correctionFor("whys"))
        assertEquals("Why's", c.correctionFor("Whys"))
        assertEquals("WHY'S", c.correctionFor("WHYS"))
    }

    @Test fun `a new target restarts the count`() {
        val c = LearnedWordsCore()
        c.recordReplacement("kr", "it", 1)
        c.recordReplacement("kr", "it", 2)
        assertEquals(1, c.recordReplacement("kr", "or", 3)!!.count)
        assertNull(c.correctionFor("kr"))
    }

    @Test fun `rewording is not learned`() {
        val c = LearnedWordsCore()
        assertNull(c.recordReplacement("car", "bus", 1))
        assertNull(c.recordReplacement("hello", "hi", 1))
    }

    @Test fun `delete then retype within the window records a replacement`() {
        val c = LearnedWordsCore()
        assertNull(c.onWordDeleted("kr", 1_000))       // not an autocorrection
        assertEquals("it", c.onWordCommitted("it", 3_000)!!.to)
        assertNull("used up", c.onWordCommitted("it", 4_000))
    }

    @Test fun `retyping after the window is ignored`() {
        val c = LearnedWordsCore()
        c.onWordDeleted("kr", 0)
        assertNull(c.onWordCommitted("it", LearnedWordsCore.REPLACE_WINDOW_MILLIS + 1))
    }

    @Test fun `deleting an autocorrection is a rejection, not a replacement`() {
        val c = LearnedWordsCore()
        c.onAutocorrected("enna", "Emma", 0)
        assertEquals("enna", c.onWordDeleted("Emma", 10))
        assertNull(c.onWordCommitted("enna", 20))
    }

    @Test fun `a kept word is never corrected`() {
        val c = LearnedWordsCore()
        c.recordReplacement("kr", "it", 1)
        c.recordReplacement("kr", "it", 2)
        c.recordRejection("kr", 3)        // user undid the learned correction
        assertNull(c.correctionFor("kr"))
    }

    @Test fun `old saved files without corrections still load`() {
        val c = LearnedWordsCore()
        c.mergeJson("""[{"word":"doni","rejections":1,"lastRejectedMillis":5}]""")
        assertTrue(c.isKept("doni"))
        assertTrue(c.allCorrections().isEmpty())
    }

    @Test fun `corrections survive a save and load`() {
        val a = LearnedWordsCore()
        a.recordRejection("vastanu", 1)
        a.recordReplacement("kr", "it", 2)
        a.recordReplacement("kr", "it", 3)
        val b = LearnedWordsCore()
        b.mergeJson(a.toJson())
        assertEquals("it", b.correctionFor("kr"))
        assertTrue(b.isKept("vastanu"))
    }

    @Test fun `clear forgets corrections too`() {
        val c = LearnedWordsCore()
        c.recordReplacement("kr", "it", 1)
        c.clear()
        assertTrue(c.allCorrections().isEmpty())
        assertNotNull(c)
    }
}
