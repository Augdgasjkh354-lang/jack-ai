package com.jack.ai.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 压缩时具体的数字、日期、约定和工具做成的事不能丢。 */
class CompressionMemoTest {
    private val call = """{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"schedule","arguments":"{\"kind\":\"reminder\",\"when\":\"2026-11-18 10:00\",\"text\":\"交材料\"}"}}]}"""
    private val result = """{"role":"tool","tool_call_id":"c1","content":"已设置：11月18日 10:00，提醒「交材料」。编号 ab12cd34。\n【核实】✓ 已保存并排好下一次"}"""
    private val msgs = listOf(
        ChatMessage(role = "user", content = "提醒我 11 月 18 号上午 10 点交材料。还有，签名钥匙先不动。今天天气不错"),
        ChatMessage(role = "assistant", content = "好的，设好了。", trail = listOf(call, result)),
    )

    @Test
    fun memoKeepsNumbersDecisionsAndWhatWasDone() {
        val lines = Compression.memoLines(msgs)
        assertTrue(lines.toString(), lines.contains("用户：提醒我 11 月 18 号上午 10 点交材料"))
        assertTrue(lines.toString(), lines.any { it.startsWith("做了：已设置：11月18日 10:00") && "ab12cd34" in it })
        // 没有数字、也不是约定的闲聊不收；【核实】这类系统标记不收
        assertFalse(lines.any { "天气" in it }); assertFalse(lines.any { "核实" in it })
    }

    @Test
    fun memoIsAppendedAndCarriedOverButNotSentBackToTheModel() {
        val first = Compression.withMemo("## 对话主线\n交材料", "", msgs)
        assertTrue(first, first.contains(Compression.MEMO) && first.contains("- 用户：提醒我 11 月 18 号上午 10 点交材料"))
        // 再压缩一次：模型只看到正文；旧备忘接着保留，新的接在后面，不重复
        val req = Compression.buildRequest(first, listOf(ChatMessage(role = "user", content = "预算改成 3000 元")), imagesOk = false)
        val sent = req[1]["content"]!!.jsonArray.joinToString { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        assertFalse(sent, sent.contains(Compression.MEMO))
        val second = Compression.withMemo("## 对话主线\n交材料、预算", first, listOf(ChatMessage(role = "user", content = "预算改成 3000 元")))
        val memo = second.substringAfter(Compression.MEMO).lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- 用户：提醒我 11 月 18 号上午 10 点交材料", "- 用户：还有，签名钥匙先不动", "- 做了：已设置：11月18日 10:00，提醒「交材料」。编号 ab12cd34。", "- 用户：预算改成 3000 元"), memo)
    }

    @Test
    fun toolRecordsReachTheModel() {
        val req = Compression.buildRequest("", msgs, imagesOk = false)
        val sent = req[1]["content"]!!.jsonArray.joinToString { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue(sent, sent.contains("[工具记录]") && sent.contains("调用：schedule") && sent.contains("编号 ab12cd34"))
    }

    @Test
    fun secretsStayOutOfTheMemo() {
        val m = listOf(ChatMessage(role = "user", content =
            "验证码是 482913。我手机 13812345678。GitHub 令牌给你 ghp_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6。key=sk-abc123def456ghi789jkl。周五 3 点开会"))
        val lines = Compression.memoLines(m)
        assertEquals(listOf("用户：周五 3 点开会"), lines)
        // 以前混进备忘的也在下次压缩时清掉；提交号、Notion 页面 id 这类十六进制编号照留
        val prev = "正文\n\n${Compression.MEMO}\n- 用户：验证码 482913\n- 做了：已写进 Notion，id=0f3a9c1e-2b4d-4e6f-8a1b-2c3d4e5f6a7b"
        val memo = Compression.withMemo("正文", prev, emptyList()).substringAfter(Compression.MEMO)
        assertFalse(memo, "482913" in memo)
        assertTrue(memo, "0f3a9c1e-2b4d-4e6f-8a1b-2c3d4e5f6a7b" in memo)
    }

    @Test
    fun aRevokedDecisionIsReplacedNotKept() {
        val first = Compression.withMemo("正文", "", listOf(ChatMessage(role = "user", content = "浏览器下载功能先不做")))
        val second = Compression.withMemo("正文", first, listOf(ChatMessage(role = "user", content = "浏览器下载功能还是做吧")))
        val third = Compression.withMemo("正文", second, listOf(ChatMessage(role = "user", content = "预算 3000 元")))
        val memo = third.substringAfter(Compression.MEMO).lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- 用户：浏览器下载功能还是做吧", "- 用户：预算 3000 元"), memo)
        // 只是数字一样、说的不是一件事：不算改掉
        assertFalse(Compression.supersedes("用户：开会改成 10 点", "用户：提醒我 11 月 18 号上午 10 点交材料"))
        assertTrue(Compression.supersedes("用户：改成每天 9 点提醒", "用户：每天 8 点提醒"))
    }
}
