package com.jack.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接着做之前告诉模型：上次已经做过哪些往外做的事，哪一步结果不明。 */
class DoneSoFarTest {
    private fun call(id: String, name: String, args: String) =
        """{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${"\"" + args.replace("\"", "\\\"") + "\""}}}]}"""
    private fun result(id: String, text: String) = """{"role":"tool","tool_call_id":"$id","content":"$text"}"""

    @Test
    fun listsSideEffectsWithResults() {
        val trail = listOf(
            call("a", "web_search", """{"query":"报价"}"""), result("a", "搜到 5 条"),
            call("b", "send_email", """{"to":"li@x.com"}"""), result("b", "已发送"),
            call("c", "add_event", """{"title":"开会"}"""),
        )
        val note = DoneSoFar.note(trail)
        assertFalse("只读的不列", note.contains("web_search"))
        assertTrue(note.contains("send_email") && note.contains("已发送"))
        assertTrue(note.contains("add_event") && note.contains("结果不明"))
    }

    @Test
    fun nothingDoneMeansNoNote() {
        assertEquals("", DoneSoFar.note(listOf(call("a", "read_file", """{"name":"x"}"""), result("a", "内容"))))
        assertEquals("", DoneSoFar.note(emptyList()))
    }
}
