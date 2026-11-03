package com.jack.ai.chat

import com.jack.ai.api.ApiException
import com.jack.ai.api.StreamEvent
import com.jack.ai.tools.LoopGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.IOException

class LongRunAuditTest {
    private val someTools = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("function")))))

    private fun user(text: String) = JsonObject(
        mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(text)),
    )

    private fun toolResult(text: String) = JsonObject(
        mapOf("role" to JsonPrimitive("tool"), "content" to JsonPrimitive(text)),
    )

    @Test
    fun slimShouldRunBeforeTheFirstRequest() = runBlocking {
        var slimmed = false
        var sawSlimmed = false
        val agent = Agent({ _, _ -> flow {
            sawSlimmed = slimmed
            emit(StreamEvent.Text("", "完成"))
        } })

        agent.run(
            messages = mutableListOf(user("继续")),
            tools = null,
            maxRounds = { 1 },
            execute = { _, _ -> "" },
            slim = { slimmed = true },
        )

        assertTrue("第一轮请求前应该先执行上下文瘦身", sawSlimmed)
    }

    @Test
    fun contextSlimShouldEnforceItsBudgetForManySmallResults() {
        val messages = MutableList(100) { toolResult("x".repeat(1_000)) }

        ContextSlim.slim(messages)

        val chars = messages.sumOf { it["content"]!!.jsonPrimitive.content.length }
        assertTrue(
            "瘦身后工具结果仍有 " + chars + " 字，超过 " + ContextSlim.BUDGET,
            chars <= ContextSlim.BUDGET,
        )
    }

    @Test
    fun transientRetryBudgetShouldResetAfterSuccessfulRounds() = runBlocking {
        val oldDelays = Agent.RETRY_DELAYS
        Agent.RETRY_DELAYS = longArrayOf(0, 0)
        try {
            fun hiccup(): () -> Flow<StreamEvent> = { flow { throw IOException("reset") } }
            fun call(id: String): () -> Flow<StreamEvent> = { flow {
                emit(StreamEvent.ToolCall(0, id, "web_search", "{}"))
            } }
            fun answer(): () -> Flow<StreamEvent> = { flow {
                emit(StreamEvent.Text("", "完成"))
            } }
            val rounds = ArrayDeque(listOf(
                hiccup(), call("call_1"),
                hiccup(), call("call_2"),
                hiccup(), answer(),
            ))
            val agent = Agent({ _, _ -> rounds.removeFirst().invoke() })

            agent.run(
                messages = mutableListOf(user("长任务")),
                tools = someTools,
                maxRounds = { 10 },
                execute = { _, _ -> "ok" },
            )

            assertEquals("完成", agent.content.toString())
        } finally {
            Agent.RETRY_DELAYS = oldDelays
        }
    }

    @Test
    fun loopGuardShouldTreatEquivalentJsonAsTheSameCall() {
        val guard = LoopGuard()

        assertNull(guard.check("web_search", """{"query":"x"}"""))
        assertNull(guard.check("web_search", """{"query" : "x"}"""))
        assertNotNull(guard.check("web_search", """{"query"  : "x"}"""))
    }

    @Test
    fun loopGuardShouldFallBackForInvalidJson() {
        val guard = LoopGuard()

        assertNull(guard.check("web_search", """{"query":"""))
        assertNull(guard.check("web_search", """  {"query":  """))
        assertNotNull(guard.check("web_search", """{"query":"""))
    }

    @Test
    @Ignore("audit: 复现")
    fun generic413ShouldNotBeAssumedToBeAnImageBudgetError() {
        assertFalse(ImageOffload.rejected(ApiException("request entity too large", 413)))
    }
}
