package com.jack.ai.chat

import com.jack.ai.api.ApiException
import com.jack.ai.api.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一次回答的循环：流式请求 → 模型要调工具就执行 → 把结果发回去再请求，直到模型直接回答。
 * 聊天和后台定时任务共用。stream 由调用方传入（方便测试时换成假的）。
 */
class Agent(
    private val stream: (messages: List<JsonObject>, tools: JsonArray?) -> Flow<StreamEvent>,
    /** DeepSeek 开思考时，带工具调用的回合必须附上思考内容。 */
    private val sendReasoning: Boolean = false,
) {
    /** 到目前为止的思考内容、正文，以及工具调用过程（JSON，之后要原样发回接口）。 */
    val reasoning = StringBuilder()
    val content = StringBuilder()
    val trail = mutableListOf<String>()
    /** 最后一轮请求说的话（中间轮次“我先搜一下”之类的不算），后台任务只要这个。 */
    var lastRound: String = ""
        private set

    /**
     * 第一轮还没出字就被接口拒绝（400）时怎么补救：返回换过的消息和工具，返回 null 就不补救、直接报错。
     * attempt 从 1 开始，每补救一次加 1。
     */
    fun interface Recovery {
        suspend fun recover(attempt: Int): Pair<List<JsonObject>, JsonArray?>?
    }

    private class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    /**
     * maxRounds 每轮都重新读（用了「深度研究」技能后会放宽）。到了上限就不再给工具，模型只能直接回答。
     * onText 每来一段字调用一次，onRound 每轮请求和每轮工具执行完调用一次。
     */
    suspend fun run(
        messages: MutableList<JsonObject>,
        tools: JsonArray?,
        maxRounds: () -> Int,
        execute: suspend (name: String, args: String) -> String,
        onText: () -> Unit = {},
        onUsage: (StreamEvent.Usage) -> Unit = {},
        onRound: () -> Unit = {},
        recovery: Recovery? = null,
        /** 用户在回答进行中插的话：每做完一轮工具取一次，放进下一轮请求。 */
        interjections: () -> List<String> = { emptyList() },
    ) {
        var currentTools = tools
        var round = 0
        var attempts = 0
        while (true) {
            val calls = sortedMapOf<Int, Call>()
            val roundReasoning = StringBuilder()
            val roundContent = StringBuilder()
            if (content.isNotEmpty() && !content.endsWith("\n")) content.append("\n\n")
            try {
                stream(messages, currentTools).collect { ev ->
                    when (ev) {
                        is StreamEvent.Text -> {
                            reasoning.append(ev.reasoning); roundReasoning.append(ev.reasoning)
                            content.append(ev.content); roundContent.append(ev.content)
                            onText()
                        }
                        is StreamEvent.Usage -> onUsage(ev)
                        is StreamEvent.ToolCall -> {
                            // 工具调用是分片流式返回的，按 index 拼起来
                            val c = calls.getOrPut(ev.index) { Call() }
                            if (ev.id.isNotEmpty()) c.id = ev.id
                            if (ev.name.isNotEmpty()) c.name = ev.name
                            c.args.append(ev.arguments)
                        }
                    }
                }
            } catch (e: ApiException) {
                if (recovery != null && round == 0 && e.code == 400 && roundContent.isEmpty()) {
                    val fix = recovery.recover(++attempts)
                    if (fix != null) {
                        messages.clear(); messages.addAll(fix.first)
                        currentTools = fix.second
                        continue
                    }
                }
                throw e
            }
            lastRound = roundContent.toString()
            onRound()
            if (calls.isEmpty()) return

            round++
            val assistantCall = buildJsonObject {
                put("role", "assistant")
                if (roundContent.isEmpty()) put("content", JsonNull) else put("content", roundContent.toString())
                if (sendReasoning) put("reasoning_content", roundReasoning.toString())
                put("tool_calls", buildJsonArray {
                    calls.values.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.args.toString())
                            })
                        })
                    }
                })
            }
            messages += assistantCall
            trail += assistantCall.toString()
            for (c in calls.values) {
                val result = execute(c.name, c.args.toString())
                val toolMsg = buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", c.id)
                    put("content", result)
                }
                messages += toolMsg
                trail += toolMsg.toString()
            }
            interjections().forEach { text ->
                val m = buildJsonObject {
                    put("role", "user")
                    put("content", "【用户在你干活时插话，考虑进去后继续】\n$text")
                }
                messages += m
                trail += m.toString()
            }
            onRound()
            if (round >= maxRounds()) currentTools = null
        }
    }
}
