package com.jack.ai.data.games

import android.content.Context
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.Endpoint
import com.jack.ai.data.Store
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.withLock

/**
 * 游戏里一个由模型扮演的角色：自己一份上下文，和别的角色互不相通（各自只知道自己的手牌）。
 * 省钱的做法：
 * - 上下文只往后加、不改前面的，每次只发「上次之后新发生的事」，前面整段都能命中缓存；
 * - 不开思考、输出限得很短（一个 JSON 动作加一句台词）；
 * - 太长了才整段换新（带上游戏给的前情：对各人的长期统计、最近的亮牌），换新那一次缓存接不上，之后又接上。
 */
class GameMind(
    private val ctx: Context,
    val ep: Endpoint,
    private val system: String,
    /** 用量里记成什么（「游戏 · 德州扑克」）。 */
    private val label: String,
    private val maxTurns: Int = 40,
) {
    private val messages = mutableListOf(sys(system))
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val client by lazy { DeepSeekClient(Store(ctx).usage(label)) }

    private fun sys(t: String) = buildJsonObject { put("role", "system"); put("content", t) }
    private fun msg(role: String, t: String): JsonObject = buildJsonObject { put("role", role); put("content", t) }

    /** 问一次；失败抛异常（调用方退回规则走法）。[recap] 是上下文太长要换新时留下的前情。 */
    suspend fun ask(user: String, maxTokens: Int = 160, json: Boolean = true, recap: () -> String = { "" }): String = lock.withLock {
        if (messages.size > maxTurns * 2) {
            val r = recap()
            messages.clear(); messages += sys(system)
            if (r.isNotBlank()) { messages += msg("user", "（前情）$r"); messages += msg("assistant", "好的。") }
        }
        val out = client.complete(ep, messages + msg("user", user), maxTokens = maxTokens, jsonMode = json)
        messages += msg("user", user)
        messages += msg("assistant", out.trim())
        out
    }
}
