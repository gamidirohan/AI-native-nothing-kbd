package org.futo.inputmethod.latin.uix.actions.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {
    private val whatsapp = """
        [21/11, 06:48] Mathi MBRDI: I’m home babe
        [21/11, 06:50] Rohan Gamidi: That's awesome babe, mum and dad happy to see you?
        [21/11, 06:50] Mathi MBRDI: Yesssss
        [21/11, 06:50] Mathi MBRDI: Dad cried 😶‍🌫️😶‍🌫️
        [21/11, 06:52] Rohan Gamidi: You do after? 😂
        [21/11, 06:53] Mathi MBRDI: Aahnnnn 😭
    """.trimIndent()

    @Test fun `whatsapp copy format is read with names`() {
        val chat = ChatTranscript.parse(whatsapp)
        assertTrue(chat.hasNames)
        assertEquals(listOf("Mathi MBRDI", "Rohan Gamidi"), chat.senders)
        assertEquals(6, chat.messages.size)
        assertEquals("Mathi MBRDI", chat.messages.last().sender)
        assertEquals("Aahnnnn 😭", chat.messages.last().text)
    }

    @Test fun `export format and 12-hour times`() {
        val chat = ChatTranscript.parse(
            "21/11/2026, 06:48 - Priya: hey are we on for saturday?\n[21/11/26, 6:50 PM] Rohan: yes!"
        )
        assertEquals(listOf("Priya", "Rohan"), chat.senders)
        assertEquals("yes!", chat.messages[1].text)
    }

    @Test fun `multi-line messages stay together`() {
        val chat = ChatTranscript.parse("[21/11, 06:48] A: first line\nsecond line\n[21/11, 06:49] B: ok")
        assertEquals(2, chat.messages.size)
        assertEquals("first line\nsecond line", chat.messages[0].text)
    }

    @Test fun `text without names has no senders`() {
        val chat = ChatTranscript.parse("I’m home babe\nThat's awesome babe\n\nYesssss")
        assertFalse(chat.hasNames)
        assertEquals(3, chat.messages.size)
    }

    @Test fun `a colon inside a plain message is not a name`() {
        val chat = ChatTranscript.parse("Note: buy milk")
        assertFalse(chat.hasNames)
    }

    @Test fun `only recent messages are kept`() {
        val text = (1..30).joinToString("\n") { "[21/11, 06:${10 + it}] A: message $it" }
        val chat = ChatTranscript.parse(text)
        assertEquals(12, chat.messages.size)
        assertEquals("message 30", chat.messages.last().text)
    }

    @Test fun `prompt is written as the user with short names`() {
        val chat = ChatTranscript.parse(whatsapp)
        val p = ChatTranscript.replyPrompt(chat, "Rohan Gamidi", null)
        assertTrue(p.system.startsWith("You are Rohan, chatting on WhatsApp with Mathi."))
        assertTrue(p.user.contains("Mathi: Yesssss\n"))
        assertTrue(p.user.trimEnd().endsWith("Rohan:"))
    }

    @Test fun `intent is included`() {
        val chat = ChatTranscript.parse(whatsapp)
        val p = ChatTranscript.replyPrompt(chat, "Rohan Gamidi", "haha i knew it")
        assertTrue(p.user.contains("What Rohan wants to say: haha i knew it"))
    }

    @Test fun `unknown user falls back to replying to the last message`() {
        val chat = ChatTranscript.parse(whatsapp)
        val p = ChatTranscript.replyPrompt(chat, null, null)
        assertTrue(p.system.startsWith("You are the user"))
        assertTrue(p.user.endsWith("My reply:"))
    }

    @Test fun `short names keep full names when first names clash`() {
        val names = ChatTranscript.shortNames(listOf("Rohan Gamidi", "Rohan K", "Mathi"))
        assertEquals("Rohan Gamidi", names["Rohan Gamidi"])
        assertEquals("Mathi", names["Mathi"])
    }
}
