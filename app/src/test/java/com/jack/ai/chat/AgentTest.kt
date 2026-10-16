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
}
