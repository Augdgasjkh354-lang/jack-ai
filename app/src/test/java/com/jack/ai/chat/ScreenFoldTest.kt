package com.jack.ai.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFoldTest {
    private fun tool(i: Int) = buildJsonObject {
        put("role", "tool"); put("tool_call_id", "c$i")
        put("content", "已点「第 $i 个」。\n【手机屏幕】微信（com.tencent.mm）\n" + (1..40).joinToString("\n") { "[$it] 按钮「条目 $it」" })
    }
    private fun call(i: Int) = buildJsonObject { put("role", "assistant"); put("content", "第 $i 步") }
    private fun content(m: JsonObject) = (m["content"] as JsonPrimitive).content

    @Test fun keepsRecentScreensAndFoldsOldOnesInBatches() {
        val msgs = mutableListOf<JsonObject>(buildJsonObject { put("role", "user"); put("content", "帮我逐个领奖励") })
        repeat(10) { msgs += call(it); msgs += tool(it) }
        // 10 屏：还没超过 2 + 8，不动
        assertEquals(0, ContextSlim.foldScreens(msgs))
        msgs += call(10); msgs += tool(10)
        assertEquals(9, ContextSlim.foldScreens(msgs))
        val tools = msgs.filter { it["role"] == JsonPrimitive("tool") }
        val first = content(tools.first())
        assertTrue(first.startsWith("已点「第 0 个」。"))
        assertTrue(ContextSlim.SCREEN_GONE in first)
        assertFalse("[1] 按钮" in first)
        // 最近两屏原样
        assertTrue(tools.takeLast(2).all { "[40] 按钮「条目 40」" in content(it) })
        // 省过的不再变：再来几步、没攒够不动，前面一字不差（缓存接得上）
        val before = msgs.map { it.toString() }
        repeat(3) { msgs += call(20 + it); msgs += tool(20 + it) }
        assertEquals(0, ContextSlim.foldScreens(msgs))
        assertEquals(before, msgs.take(before.size).map { it.toString() })
    }

    @Test fun slimReplayIsDeterministic() {
        val base = mutableListOf<JsonObject>(buildJsonObject { put("role", "user"); put("content", "开始") })
        repeat(25) { base += call(it); base += tool(it) }
        val a = base.toMutableList(); ContextSlim.replay(a)
        val b = base.toMutableList(); ContextSlim.replay(b)
        assertEquals(a, b)
        assertTrue(a.count { ContextSlim.SCREEN_GONE in (it["content"] as JsonPrimitive).content } >= 16)
    }
}
