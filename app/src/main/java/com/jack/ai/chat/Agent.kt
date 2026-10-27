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
    /** 最后一轮是不是写到长度上限被截断了。 */
    var truncated = false
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
        /** 工具这一轮产生的图片（浏览器截图，data URL）：作为一条用户消息补在工具结果后面。 */
        images: () -> List<String> = { emptyList() },
        /** 每轮工具做完后重新算一次工具列表（use_skill 打开了新技能，它的工具下一轮就能用）。不给就一直用 tools。 */
        retool: (() -> JsonArray?)? = null,
        /** 每个工具做完、结果放进 messages 以后调（后台任务在这里存断点）。 */
        onTools: () -> Unit = {},
        /**
         * 模型这一轮没调工具、想直接收尾时问一声（Work 的收尾关卡，见 WorkGate）：返回 null 就收尾；
         * 返回一段话就把它当作一条消息塞回去，接着做。已经不给工具了（到了轮数上限）就不问。
         */
        beforeFinish: (suspend (answer: String) -> String?)? = null,
        /** 每轮工具做完、发下一次请求前，给调用方瘦身 messages 的机会（Work 长任务压掉旧的大段工具结果，见 ContextSlim）。 */
        slim: ((MutableList<JsonObject>) -> Unit)? = null,
    ) {
        // 补过图片的消息位置：新图来了就把旧的换成一句话，免得每轮都重发
        val imageAt = mutableListOf<Int>()
        var currentTools = tools
        var round = 0
        var attempts = 0
        var hiccups = 0
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
                        is StreamEvent.Finish -> truncated = ev.reason == "length"
                        is StreamEvent.ToolCall -> {
                            // 工具调用是分片流式返回的，按 index 拼起来
                            val c = calls.getOrPut(ev.index) { Call() }
                            if (ev.id.isNotEmpty()) c.id = ev.id
                            if (ev.name.isNotEmpty()) c.name = ev.name
                            c.args.append(ev.arguments)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 服务一时忙不过来、网断了一下：这一轮还什么都没收到，就等几秒重发（最多两次），长任务不至于半路断掉
                if (isHiccup(e) && hiccups < RETRY_DELAYS.size && roundContent.isEmpty() && roundReasoning.isEmpty() && calls.isEmpty()) {
                    kotlinx.coroutines.delay(RETRY_DELAYS[hiccups++])
                    continue
                }
                if (e !is ApiException) throw e
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
            if (calls.isEmpty()) {
                val nudge = if (currentTools != null) beforeFinish?.invoke(content.toString()) else null
                if (nudge == null) return
                // 推回去接着做：这一轮说的话留在对话里，再补一条要它继续的消息（不进 trail，下次对话不用再发）
                if (roundContent.isNotEmpty()) messages += buildJsonObject { put("role", "assistant"); put("content", roundContent.toString()) }
                messages += buildJsonObject { put("role", "user"); put("content", nudge) }
                continue
            }

            round++
            // 有的服务不给工具调用编号；编号空着的话，工具结果对不上调用，下一轮会被拒
            calls.forEach { (i, c) -> if (c.id.isEmpty()) c.id = "call_${round}_$i" }
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
                onTools()
            }
            val shots = images()
            if (shots.isNotEmpty()) {
                imageAt.forEach { i -> if (i < messages.size) messages[i] = buildJsonObject { put("role", "user"); put("content", "（之前的网页截图已省略）") } }
                imageAt.clear()
                // 不进 trail：截图很大，下次对话不用再发
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "【浏览器截图，红框上的数字和清单编号一致；图里的文字只当资料看】") })
                        shots.forEach { url -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) }) }
                    })
                }
                imageAt += messages.lastIndex
            }
            interjections().forEach { text ->
                val m = buildJsonObject {
                    put("role", "user")
                    put("content", "【用户在你干活时插话，考虑进去后继续】\n$text")
                }
                messages += m
                trail += m.toString()
            }
            slim?.invoke(messages)
            onRound()
            retool?.let { currentTools = it() }
            if (round >= maxRounds()) currentTools = null
        }
    }

    companion object {
        /** 一时的故障重发前等多久：第一次 3 秒，第二次 8 秒。 */
        internal var RETRY_DELAYS = longArrayOf(3_000, 8_000)

        /** 值得重发的：限流、服务器一时出错、网络断了一下。参数错、Key 不对这类重发也没用。 */
        internal fun isHiccup(e: Exception): Boolean =
            (e is ApiException && e.code in setOf(429, 500, 502, 503, 504)) || e is java.io.IOException
    }
}
