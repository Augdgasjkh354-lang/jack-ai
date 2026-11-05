package com.jack.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakedToolCallTest {
    private val tools = listOf("web_search", "phone")

    @Test fun findsDeepSeekMarkerFollowedByOurTool() {
        val text = "我查一下。<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>web_search\n```json\n{\"query\":\"x\"}```"
        assertEquals("web_search", LeakedToolCall.find(text, tools))
        assertEquals("我查一下。", LeakedToolCall.before(text))
    }

    @Test fun ignoresPlainTalkAboutTools() {
        assertNull(LeakedToolCall.find("可以用 web_search 搜一下，或者 phone 打开 App", tools))
        assertNull(LeakedToolCall.find("格式是 <tool_call> 加 JSON", tools))
    }
}
