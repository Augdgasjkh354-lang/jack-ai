package com.jack.ai.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长任务瘦身：超了才压，只压较早的大段工具结果，链接和结尾留着，压过的不再压。 */
class ContextSlimTest {
    private fun tool(text: String) = JsonObject(mapOf("role" to JsonPrimitive("tool"), "content" to JsonPrimitive(text)))
    private fun content(m: JsonObject) = m["content"]!!.jsonPrimitive.content

    @Test
    fun onlyOldBigResultsAreTrimmedOnceOverBudget() {
        val big = "开头" + "正文".repeat(6000) + " https://a.com/x " + "正文".repeat(100) + "\n【核实】✓ 已生成"
        val small = mutableListOf(tool(big), tool(big))
        assertEquals(0, ContextSlim.slim(small))  // 没超不动
        val msgs = MutableList(ContextSlim.KEEP_RECENT + 3) { tool(big) }
        msgs.add(1, tool("短的结果"))
        val n = ContextSlim.slim(msgs)
        assertEquals(3, n)
        val trimmed = content(msgs[0])
        assertTrue(trimmed.startsWith(ContextSlim.MARK))
        assertTrue(trimmed.contains("https://a.com/x"))
        assertTrue(trimmed.endsWith("【核实】✓ 已生成"))
        assertEquals("短的结果", content(msgs[1]))
        assertEquals(big, content(msgs.last()))  // 最近的原样
        assertEquals(0, ContextSlim.slim(msgs).let { if (it > 0) -1 else 0 }.coerceAtMost(0))
    }

    @Test
    fun spilledResultsPointToTheSavedFile() {
        val big = "开头" + "正文".repeat(6000) + "\n结尾"
        val msgs = MutableList(ContextSlim.KEEP_RECENT + 2) { tool(big + it) }
        val saved = mutableListOf<String>()
        ContextSlim.slim(msgs) { saved += it; ".spill/r000000000000.txt" }
        assertEquals(2, saved.size)
        assertEquals(big + 0, saved[0])  // 存的是全文
        val trimmed = content(msgs[0])
        assertTrue(trimmed.startsWith(ContextSlim.MARK))
        assertTrue(trimmed.contains("read_file (name = .spill/r000000000000.txt)"))
        // 存不了就照旧提示重新调工具
        val m2 = MutableList(ContextSlim.KEEP_RECENT + 1) { tool(big + it) }
        ContextSlim.slim(m2) { null }
        assertTrue(content(m2[0]).contains("Call the tool again"))
    }
}
