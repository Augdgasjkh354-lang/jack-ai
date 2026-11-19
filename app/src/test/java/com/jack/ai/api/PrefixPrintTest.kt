package com.jack.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixPrintTest {
    private fun m(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private val tools = JsonArray(listOf(JsonPrimitive("t1")))

    @Test fun findsWhichPartChanged() {
        val base = listOf(m("system", "S"), m("user", "背景+你好"), m("assistant", "嗨"))
        val a = PrefixPrint.of("v4-flash", "low", base, tools)
        // 后面多了消息：开头没变
        assertNull(PrefixPrint.firstChange(a, PrefixPrint.of("v4-flash", "low", base + m("user", "下一句"), tools)))
        assertEquals("模型或思考深浅", PrefixPrint.firstChange(a, PrefixPrint.of("v4-flash", "high", base, tools)))
        assertEquals("系统提示词", PrefixPrint.firstChange(a, PrefixPrint.of("v4-flash", "low", listOf(m("system", "S2")) + base.drop(1), tools)))
        assertEquals("工具说明", PrefixPrint.firstChange(a, PrefixPrint.of("v4-flash", "low", base, JsonArray(emptyList()))))
        assertEquals("对话开头（背景、记忆、第一条消息）", PrefixPrint.firstChange(a, PrefixPrint.of("v4-flash", "low", listOf(m("system", "S"), m("user", "新背景+你好")), tools)))
        assertEquals("", PrefixPrint.firstChange("", a))
    }
}
