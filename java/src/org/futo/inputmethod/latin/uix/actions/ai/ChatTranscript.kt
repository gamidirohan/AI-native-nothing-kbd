package org.futo.inputmethod.latin.uix.actions.ai

/**
 * Reads chat text copied from WhatsApp (and similar apps) so a Reply can be written as the user.
 *
 * WhatsApp copies several selected messages as `[21/11, 06:48] Mathi MBRDI: I'm home babe`,
 * and chat exports as `21/11/2026, 06:48 - Mathi MBRDI: I'm home babe`. A single copied message
 * is plain text with no names; then who said what is unknown.
 */
object ChatTranscript {
    data class Message(val sender: String?, val text: String)

    data class Chat(
        val messages: List<Message>,
        /** Distinct senders in order of first appearance; empty if the text had no names. */
        val senders: List<String>
    ) {
        val hasNames: Boolean get() = senders.isNotEmpty()
    }

    // [21/11, 06:48] Name: text      21/11/2026, 06:48 - Name: text      [21/11/26, 6:48 PM] Name: text
    private val line = Regex(
        """^\u200E?\[?\s*\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?,?\s+\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?\s*\]?\s*(?:-\s*)?([^:\n]{1,50}?):\s?(.*)$"""
    )

    /** Keep at most this many recent messages, and roughly this many characters. */
    private const val MAX_MESSAGES = 12
    private const val MAX_CHARS = 1500

    fun parse(text: String): Chat {
        val messages = mutableListOf<Message>()
        var sawNamedLine = false
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val match = line.find(raw.trim())
            if (match != null) {
                sawNamedLine = true
                messages.add(Message(match.groupValues[1].trim(), match.groupValues[2].trim()))
            } else if (raw.isNotBlank()) {
                val last = messages.lastOrNull()
                if (sawNamedLine && last != null) {
                    // A message that spans several lines.
                    messages[messages.lastIndex] = last.copy(text = last.text + "\n" + raw.trim())
                } else {
                    messages.add(Message(null, raw.trim()))
                }
            }
        }

        val recent = trimToRecent(messages.filter { it.text.isNotBlank() })
        val senders = if (sawNamedLine) recent.mapNotNull { it.sender }.distinct() else emptyList()
        return Chat(recent, senders)
    }

    private fun trimToRecent(messages: List<Message>): List<Message> {
        val kept = ArrayDeque<Message>()
        var chars = 0
        for (m in messages.asReversed()) {
            if (kept.size >= MAX_MESSAGES) break
            if (kept.isNotEmpty() && chars + m.text.length > MAX_CHARS) break
            kept.addFirst(m)
            chars += m.text.length
        }
        return kept.toList()
    }

    /** "Rohan Gamidi" -> "Rohan", unless two senders share a first name. */
    fun shortNames(senders: List<String>): Map<String, String> {
        val first = senders.associateWith { it.split(' ').firstOrNull { p -> p.isNotBlank() } ?: it }
        val clash = first.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return senders.associateWith { if (first[it] in clash) it else first[it]!! }
    }

    data class Prompt(val system: String, val user: String)

    /**
     * Builds the Reply prompt. [me] is the user's own name as it appears in the chat, if known.
     * [intent] is what the user wants to say (their draft), if any.
     */
    fun replyPrompt(chat: Chat, me: String?, intent: String?): Prompt {
        val wants = intent?.trim()?.takeIf { it.isNotEmpty() }

        if (!chat.hasNames || me == null || me !in chat.senders) {
            val transcript = chat.messages.joinToString("\n") { it.text }
            val system = "You are the user, replying to a chat. Write the user's next message, " +
                    "replying to the last message. One or two short sentences, casual, in the " +
                    "same style and language. Output only the message."
            val user = buildString {
                append(transcript)
                if (wants != null) append("\n\nWhat I want to say: ").append(wants)
                append("\n\nMy reply:")
            }
            return Prompt(system, user)
        }

        val names = shortNames(chat.senders)
        val myName = names[me]!!
        val others = chat.senders.filter { it != me }.map { names[it]!! }
        val withWhom = when (others.size) {
            0 -> "a friend"
            1 -> others[0]
            else -> others.dropLast(1).joinToString(", ") + " and " + others.last()
        }
        val system = "You are $myName, chatting on WhatsApp with $withWhom. Read the chat and " +
                "write $myName's next message. One or two short sentences, casual, in the same " +
                "style and language. Output only the message."
        val user = buildString {
            chat.messages.forEach { append(names[it.sender] ?: it.sender).append(": ").append(it.text).append('\n') }
            if (wants != null) append("\nWhat $myName wants to say: ").append(wants).append("\n\n")
            append(myName).append(':')
        }
        return Prompt(system, user)
    }
}
