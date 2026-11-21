package com.jack.ai.a11y

import com.jack.ai.chat.VoiceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAskRegionTest {
    private val lines = listOf(
        ScreenAsk.Line("标题", listOf(0, 0, 1000, 100)),
        ScreenAsk.Line("价格 99 元", listOf(100, 500, 400, 560)),
        ScreenAsk.Line("加入购物车", listOf(600, 1800, 1000, 1900)),
    )

    @Test fun picksOnlyTheWordsInsideTheCircle() {
        assertEquals(listOf("价格 99 元"), ScreenAsk.inside(lines, 50, 450, 500, 650))
        // 只盖住标题的一小角不算
        assertEquals(emptyList<String>(), ScreenAsk.inside(lines, 900, 80, 1080, 300))
        assertEquals("「微信」屏幕上的内容：\n标题\n价格 99 元\n加入购物车\n", ScreenAsk.allText("微信", lines))
    }

    @Test fun voiceNoteOnlyForTheTalkingConversation() {
        try {
            VoiceMode.on = true; VoiceMode.convId = "a"
            assertEquals(VoiceMode.NOTE, VoiceMode.note("a"))
            assertEquals("", VoiceMode.note("b"))
            VoiceMode.convId = null
            assertEquals(VoiceMode.NOTE, VoiceMode.note("new"))
            VoiceMode.on = false
            assertEquals("", VoiceMode.note("a"))
        } finally { VoiceMode.on = false; VoiceMode.convId = null }
    }
}
