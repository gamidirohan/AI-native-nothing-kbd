package org.futo.inputmethod.latin.learning

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android wrapper around LearnedWordsCore. Only paths that need no Context are covered here;
 * this exists because publish() once called itself and crashed the keyboard on every start.
 */
class LearnedWordsTest {
    @Test fun `clear and remove publish without recursing`() {
        LearnedWords.clear()
        LearnedWords.remove("anything")
        LearnedWords.removeCorrection("anything")
        assertTrue(LearnedWords.words.value.isEmpty())
        assertTrue(LearnedWords.corrections.value.isEmpty())
    }
}
