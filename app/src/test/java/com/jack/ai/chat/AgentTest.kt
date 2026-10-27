package com.jack.ai.chat

import com.jack.ai.api.ApiException
import com.jack.ai.api.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTest {
    private val someTools = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("function")))))
    private fun userMsg(t: String) = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(t)))

    /** 假接口：按顺序给出每一轮的事件，记下每次请求带没带工具、带了几条消息。 */
    private class FakeApi(vararg rounds: () -> Flow<StreamEvent>) {
        val queue = ArrayDeque(rounds.toList())
        val toolsSeen = mutableListOf<JsonArray?>()
        val sizes = mutableListOf<Int>()
        fun stream(msgs: List<JsonObject>, tools: JsonArray?): Flow<StreamEvent> {
            toolsSeen += tools
            sizes += msgs.size
            return queue.removeFirst()()
        }
    }

    private fun text(vararg parts: String, reasoning: String = "") = { flow {
        if (reasoning.isNotEmpty()) emit(StreamEvent.Text(reasoning, ""))
        parts.forEach { emit(StreamEvent.Text("", it)) }
        emit(StreamEvent.Usage(100, 10, 60))
    } }

    /** 一轮里调一个工具，参数分两片到达。 */
    private fun toolCall(name: String, say: String = "", id: String = "call_1") = { flow {
        if (say.isNotEmpty()) emit(StreamEvent.Text("想一想", say))
        emit(StreamEvent.ToolCall(0, id, name, "{\"query\":"))
        emit(StreamEvent.ToolCall(0, "", "", "\"天气\"}"))
    } }

    private fun reject() = { flow<StreamEvent> { throw ApiException("bad request", 400) } }

    @Test
    fun plainAnswer() = runBlocking {
        val api = FakeApi(text("你", "好"))
        val agent = Agent(api::stream)
        var texts = 0
        var usage: StreamEvent.Usage? = null
        agent.run(mutableListOf(userMsg("hi")), someTools, { 5 }, { _, _ -> fail("不该调工具"); "" },
            onText = { texts++ }, onUsage = { usage = it })
        assertEquals("你好", agent.content.toString())
        assertEquals("你好", agent.lastRound)
        assertEquals(2, texts)
        assertEquals(60, usage?.cacheHit)
        assertTrue(agent.trail.isEmpty())
    }

    @Test
    fun toolsAreRecomputedAfterEachToolRound() = runBlocking {
        // use_skill 打开了新技能：下一轮请求带上新的工具列表
        val more = JsonArray(someTools + JsonObject(mapOf("type" to JsonPrimitive("function"), "x" to JsonPrimitive(1))))
        val api = FakeApi(toolCall("use_skill"), text("好了"))
        var opened = false
        Agent(api::stream).run(mutableListOf(userMsg("用 GitHub")), someTools, { 5 }, { _, _ -> opened = true; "做法…" },
            retool = { if (opened) more else someTools })
        assertEquals(listOf<JsonArray?>(someTools, more), api.toolsSeen)
    }

    @Test
    fun toolCallIsAssembledExecutedAndRecorded() = runBlocking {
        val api = FakeApi(toolCall("web_search", say = "我查一下"), text("今天晴"))
        val agent = Agent(api::stream)
        val calls = mutableListOf<Pair<String, String>>()
        val messages = mutableListOf(userMsg("天气"))
        agent.run(messages, someTools, { 5 }, { n, a -> calls += n to a; "晴，25 度" })
        assertEquals(listOf("web_search" to "{\"query\":\"天气\"}"), calls)
        // 两轮之间的话用空行隔开；后台任务只取最后一轮
        assertEquals("我查一下\n\n今天晴", agent.content.toString())
        assertEquals("今天晴", agent.lastRound)
        assertEquals(2, agent.trail.size)
        assertEquals(listOf(1, 3), api.sizes) // 第二次请求带上了 assistant 的调用和 tool 的结果
        val tool = messages[2]
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("晴，25 度", tool["content"]!!.jsonPrimitive.content)
        val fn = messages[1]["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("web_search", fn["name"]!!.jsonPrimitive.content)
        assertNull(messages[1]["reasoning_content"])
    }

    @Test
    fun reasoningIsSentBackWhenRequired() = runBlocking {
        val api = FakeApi(toolCall("web_search", say = "查"), text("好"))
        val messages = mutableListOf(userMsg("x"))
        Agent(api::stream, sendReasoning = true).run(messages, someTools, { 5 }, { _, _ -> "ok" })
        assertEquals("想一想", messages[1]["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsAreWithdrawnAfterMaxRounds() = runBlocking {
        val api = FakeApi(toolCall("a"), toolCall("b"), text("只能直接答了"))
        val agent = Agent(api::stream)
        var executed = 0
        agent.run(mutableListOf(userMsg("x")), someTools, { 2 }, { _, _ -> executed++; "r" })
        assertEquals(2, executed)
        assertNotNull(api.toolsSeen[0])
        assertNotNull(api.toolsSeen[1])
        assertNull(api.toolsSeen[2])
    }

    @Test
    fun maxRoundsIsReadEveryRound() = runBlocking {
        // 用了「深度研究」后上限会在中途变大
        var limit = 1
        val api = FakeApi(toolCall("use_skill"), toolCall("web_search"), text("完"))
        Agent(api::stream).run(mutableListOf(userMsg("x")), someTools, { limit }, { _, _ -> limit = 25; "r" })
        assertNotNull(api.toolsSeen[1])
    }

    @Test
    fun rejectedFirstRequestIsRecovered() = runBlocking {
        val api = FakeApi(reject(), reject(), text("先搜再答"))
        val attempts = mutableListOf<Int>()
        val agent = Agent(api::stream)
        agent.run(mutableListOf(userMsg("x")), someTools, { 5 }, { _, _ -> "" }, recovery = Agent.Recovery { a ->
            attempts += a
            if (a == 1) listOf(userMsg("flat")) to someTools else listOf(userMsg("no tools")) to null
        })
        assertEquals(listOf(1, 2), attempts)
        assertEquals(listOf(someTools, someTools, null), api.toolsSeen)
        assertEquals("先搜再答", agent.content.toString())
    }

    @Test
    fun transientFailuresAreRetriedBeforeAnythingArrives() = runBlocking {
        Agent.RETRY_DELAYS = longArrayOf(1, 1)
        val busy = { flow<StreamEvent> { throw ApiException("服务器繁忙", 503) } }
        val netDrop = { flow<StreamEvent> { throw java.io.IOException("reset") } }
        val api = FakeApi(busy, netDrop, text("好了"))
        val agent = Agent(api::stream)
        agent.run(mutableListOf(userMsg("x")), someTools, { 5 }, { _, _ -> "" })
        assertEquals("好了", agent.content.toString())
        assertEquals(3, api.sizes.size)
        // 参数错（400）不重发；连着三次忙也不再试
        val api2 = FakeApi(busy, busy, busy)
        try { Agent(api2::stream).run(mutableListOf(userMsg("x")), someTools, { 5 }, { _, _ -> "" }); fail("应该报错") }
        catch (e: ApiException) { assertEquals(503, e.code) }
        assertEquals(3, api2.sizes.size)
    }

    @Test
    fun giveUpWhenRecoveryReturnsNull() = runBlocking {
        val api = FakeApi(reject())
        try {
            Agent(api::stream).run(mutableListOf(userMsg("x")), someTools, { 5 }, { _, _ -> "" },
                recovery = Agent.Recovery { null })
            fail("应该报错")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
        }
    }

    @Test
    fun noRecoveryAfterTheModelStartedTalking() = runBlocking {
        val api = FakeApi({ flow {
            emit(StreamEvent.Text("", "说了一半"))
            throw ApiException("bad", 400)
        } })
        var asked = false
        try {
            Agent(api::stream).run(mutableListOf(userMsg("x")), someTools, { 5 }, { _, _ -> "" },
                recovery = Agent.Recovery { asked = true; null })
            fail("应该报错")
        } catch (e: ApiException) {
            assertEquals(false, asked)
        }
    }

    @Test
    fun interjectionGoesIntoNextRound() = runBlocking {
        val api = FakeApi(toolCall("web_search"), text("好的"))
        val agent = Agent(api::stream)
        val queue = ArrayDeque(listOf("顺便查一下明天"))
        val msgs = mutableListOf(userMsg("今天天气"))
        agent.run(msgs, someTools, { 5 }, { _, _ -> "晴" },
            interjections = { generateSequence { queue.removeFirstOrNull() }.toList() })
        val inserted = msgs.last()
        assertEquals("user", inserted["role"]!!.jsonPrimitive.content)
        assertTrue(inserted["content"]!!.jsonPrimitive.content.contains("顺便查一下明天"))
        assertTrue(agent.trail.any { "顺便查一下明天" in it })
        // 第二轮请求带上了插话：user + assistant(调用) + tool + 插话
        assertEquals(4, api.sizes[1])
    }

    @Test
    fun missingToolCallIdIsFilledIn() = runBlocking {
        val api = FakeApi(toolCall("web_search", id = ""), text("好了"))
        val messages = mutableListOf(userMsg("天气"))
        Agent(api::stream).run(messages, someTools, { 5 }, { _, _ -> "晴" })
        val id = messages.first { it["tool_calls"] != null }["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(id.isNotEmpty())
        assertEquals(id, messages.first { it["role"]?.jsonPrimitive?.content == "tool" }["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun screenshotsGoAfterToolResultsAndOldOnesAreDropped() = runBlocking {
        val api = FakeApi(toolCall("browser", id = "a"), toolCall("browser", id = "b"), text("看完了"))
        val agent = Agent(api::stream)
        val messages = mutableListOf(userMsg("帮我看看网页"))
        var shot = 0
        agent.run(messages, someTools, { 5 }, { _, _ -> "页面清单" }, images = { listOf("data:image/jpeg;base64,${++shot}") })
        // user, assistant, tool, 截图1（已换成一句话）, assistant, tool, 截图2
        assertEquals(7, messages.size)
        assertEquals("（之前的网页截图已省略）", messages[3]["content"]!!.jsonPrimitive.content)
        val last = messages[6]["content"]!!.jsonArray
        assertEquals("data:image/jpeg;base64,2", last[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // 截图不进 trail（下次对话不用再发）
        assertEquals(4, agent.trail.size)
        assertTrue(agent.trail.none { "base64" in it })
    }

    @Test
    fun finishHookPushesBackUntilItAllowsFinishing() = runBlocking {
        val api = FakeApi(text("做完了"), toolCall("web_search"), text("这回真做完了"))
        val agent = Agent(api::stream)
        var asked = 0
        val msgs = mutableListOf(userMsg("找 5 家供应商"))
        agent.run(msgs, someTools, { 10 }, { _, _ -> "结果" },
            beforeFinish = { asked++; if (asked == 1) "还有两步没做完" else null })
        assertEquals(2, asked)
        // 推回去的那句和第一轮说的话都在对话里，接着做了一轮工具，最后才收尾
        assertTrue(msgs.any { it["content"]?.jsonPrimitive?.content == "还有两步没做完" })
        assertTrue(msgs.any { it["role"]?.jsonPrimitive?.content == "assistant" && it["content"]?.jsonPrimitive?.content == "做完了" })
        assertEquals("这回真做完了", agent.lastRound)
        // 推回去的话不进 trail（下次对话不用再发）
        assertTrue(agent.trail.none { "还有两步没做完" in it })
    }

    @Test
    fun finishHookIsNotAskedWhenToolsAreGone() = runBlocking {
        val api = FakeApi(toolCall("web_search"), text("只能这样了"))
        val agent = Agent(api::stream)
        var asked = 0
        agent.run(mutableListOf(userMsg("查")), someTools, { 1 }, { _, _ -> "结果" },
            beforeFinish = { asked++; "接着做" })
        // 到了轮数上限、已经不给工具，推回去也做不了事，就不问
        assertEquals(0, asked)
    }

    @Test
    fun systemNoticesGoInAsTheirOwnMessage() = runBlocking {
        val api = FakeApi(toolCall("web_search"), text("好的，收尾"))
        val agent = Agent(api::stream)
        val msgs = mutableListOf(userMsg("自由时间"))
        var sent = false
        agent.run(msgs, someTools, { 10 }, { _, _ -> "结果" },
            notices = { if (!sent) { sent = true; listOf("系统：预算用完了") } else emptyList() })
        // 原样一条，不加「用户插话」那种前缀
        assertTrue(msgs.any { it["role"]?.jsonPrimitive?.content == "user" && it["content"]?.jsonPrimitive?.content == "系统：预算用完了" })
        assertEquals("好的，收尾", agent.lastRound)
    }
}
