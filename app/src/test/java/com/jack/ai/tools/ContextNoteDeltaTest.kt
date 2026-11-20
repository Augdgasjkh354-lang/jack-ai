package com.jack.ai.tools

import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import org.junit.Assert.*
import org.junit.Test

class ContextNoteDeltaTest {
    private val note1 = "【Conversation folder】a.txt (1 KB)\n【Tables】「记账」3 rows: 日期, 金额\n"

    private fun conv(vararg ids: String, summaryUntil: String? = null) = Conversation(
        messages = ids.map { ChatMessage(id = it, role = "user", content = "x", contextPrefix = "p") },
        summaryUntilId = summaryUntil,
    )

    @Test fun unchangedSectionsAreNotRepeated() {
        val (first, sent) = ContextNote.delta(note1, emptyMap(), conv(), "m1")
        assertEquals(note1, first)
        val c = conv("m1")
        val (second, sent2) = ContextNote.delta(note1, sent, c, "m2")
        assertFalse(second.contains("a.txt"))
        assertTrue(second.contains("not repeated: 【Conversation folder】, 【Tables】"))
        // 变了的那段照发，没变的不发
        val changed = "【Conversation folder】a.txt (1 KB), b.png (2 KB)\n【Tables】「记账」3 rows: 日期, 金额\n"
        val (third, sent3) = ContextNote.delta(changed, sent2, conv("m1", "m2"), "m3")
        assertTrue(third.contains("b.png"))
        assertFalse(third.contains("记账"))
        assertTrue(sent3["【Conversation folder】"]!!.endsWith("@m3"))
        assertTrue(sent3["【Tables】"]!!.endsWith("@m1"))
    }

    @Test fun goneSectionsAreAnnouncedOnce() {
        val (_, sent) = ContextNote.delta(note1, emptyMap(), conv(), "m1")
        val (now, sent2) = ContextNote.delta("【Tables】「记账」3 rows: 日期, 金额\n", sent, conv("m1"), "m2")
        assertTrue(now.contains("【Conversation folder】 none now."))
        assertFalse("【Conversation folder】" in sent2)
    }

    @Test fun compressedOrEditedAwayMeansSendAgain() {
        val (_, sent) = ContextNote.delta(note1, emptyMap(), conv(), "m1")
        // m1 被压缩进提要了
        val (again, _) = ContextNote.delta(note1, sent, conv("m1", "m2", summaryUntil = "m1"), "m3")
        assertEquals(note1, again)
        // m1 被改掉（没了）
        val (again2, _) = ContextNote.delta(note1, sent, conv("m9"), "m3")
        assertEquals(note1, again2)
    }

    @Test fun anchorIsRepeatedAfterAWhile() {
        val anchor = "【Still you】I am Kavi.\n- warm\n"
        val (_, sent) = ContextNote.delta(anchor, emptyMap(), conv(), "m1")
        val near = conv(*(listOf("m1") + (2..10).map { "m$it" }).toTypedArray())
        assertFalse(ContextNote.delta(anchor, sent, near, "m11").first.contains("I am Kavi"))
        val far = conv(*(listOf("m1") + (2..40).map { "m$it" }).toTypedArray())
        assertTrue(ContextNote.delta(anchor, sent, far, "m41").first.contains("I am Kavi"))
    }

    @Test fun sectionsSplitOnHeadersOnly() {
        val s = ContextNote.sections("intro\n【A】one\nmore\n【B】two\n")
        assertEquals(listOf("" to "intro\n", "【A】" to "【A】one\nmore\n", "【B】" to "【B】two\n"), s)
    }
}
