package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStartsTest {
    @Test fun parsesAndCleansSuggestions() {
        val raw = """```json
{"items": [
  {"title": "跟进王总的报价。", "prompt": "帮我写条消息跟进王总的报价"},
  {"title": "跟进王总的报价", "prompt": "重复的"},
  {"title": "", "prompt": "没标题"},
  {"title": "这个标题实在是太长太长了超过十六个字了吧", "prompt": "太长"},
  {"title": "周末去爬山", "prompt": "帮我规划一下周末爬山"}
]}
```"""
        assertEquals(listOf("跟进王总的报价" to "帮我写条消息跟进王总的报价", "周末去爬山" to "帮我规划一下周末爬山"), AiStarts.parse(raw))
    }

    @Test fun badOutputGivesNothing() {
        assertTrue(AiStarts.parse("不是 JSON").isEmpty())
        assertTrue(AiStarts.parse("""{"items": "x"}""").isEmpty())
    }

    @Test fun keepsAtMostTen() {
        val raw = """{"items": [""" + (1..15).joinToString(",") { """{"title": "建议$it", "prompt": "p$it"}""" } + "]}"
        assertEquals(AiStarts.COUNT, AiStarts.parse(raw).size)
    }
}
