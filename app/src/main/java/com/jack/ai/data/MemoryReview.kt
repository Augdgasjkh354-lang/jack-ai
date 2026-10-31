package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 聊着聊着就回看一下（借鉴 Hermes Agent 每轮之后的后台复盘）：每 [EVERY] 轮，用后台模型看看刚聊的几轮，
 * 有没有值得长期记住的（用户的偏好、纠正 Kavi 的话、关于他的稳定事实、哪条旧记忆说错了），有就记下。
 * Chat 记进全局记忆，Work 记进工作记忆。夜里做梦整理全天的，这个补上白天的空档：纠正过的话当场记住。
 * 大多数时候什么都不改。写满了就不写（做梦时会腾地方），一次最多 [MAX_OPS] 条。
 */
object MemoryReview {
    /** 每多少轮（用户的消息）回看一次。 */
    const val EVERY = 3
    const val MAX_OPS = 3
    /** 一次最多看最近多少条消息，每条最多多少字。 */
    private const val MAX_MESSAGES = 8
    private const val MAX_CHARS = 1500

    /** 还没回看过的消息（回答出错、空的不算）。 */
    fun unseen(conv: Conversation): List<ChatMessage> =
        conv.messages.filter { it.createdAt > conv.memReviewedAt && it.error == null && it.content.isNotBlank() }

    fun due(conv: Conversation): Boolean = unseen(conv).count { it.role == "user" } >= EVERY

    fun transcript(msgs: List<ChatMessage>, user: String): String = msgs.takeLast(MAX_MESSAGES).joinToString("\n\n") { m ->
        (if (m.role == "user") user else "You") + ": " + m.content.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "…" else it }
    }

    data class Op(val op: String, val text: String = "", val old: String = "")

    fun parse(raw: String): List<Op> {
        val obj = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull() ?: return emptyList()
        return (obj["ops"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            val op = s("op").lowercase()
            when {
                op == "add" && s("text").isNotEmpty() -> Op("add", s("text"))
                op == "update" && s("old").isNotEmpty() && s("text").isNotEmpty() -> Op("update", s("text"), s("old"))
                op == "delete" && s("old").isNotEmpty() -> Op("delete", old = s("old"))
                else -> null
            }
        }.take(MAX_OPS)
    }

    /**
     * 在现有的记忆上执行：认不出旧记忆的、重复的跳过；会超过 [budget] 的新增或变长不做。
     * 返回改完的列表和每条做了什么（给用户看的说明）。
     */
    fun apply(items: List<String>, ops: List<Op>, budget: Int): Pair<List<String>, List<String>> {
        var list = items
        val done = mutableListOf<String>()
        for (op in ops) {
            val next = runCatching {
                when (op.op) {
                    "add" -> if (list.any { it == op.text }) null else list + op.text
                    "update" -> ChatMemory.replace(list, op.old, op.text)
                    else -> ChatMemory.replace(list, op.old, "")
                }
            }.getOrNull() ?: continue
            if (MemoryOps.fullNote(list, next, budget, "") != null) continue
            list = next
            done += when (op.op) { "add" -> "记下：${op.text}"; "update" -> "改成：${op.text}"; else -> "删掉：${op.old}" }
        }
        return list to done
    }

    /** 给用户看的那条说明（挂在回答下面）。 */
    fun notice(done: List<String>, work: Boolean): String =
        (if (work) "顺手更新了工作记忆" else "顺手更新了记忆") + "\n" + done.joinToString("\n")

    const val NOTICE_PREFIX = "顺手更新了"

    fun instructions(work: Boolean): String = if (work) WORK else CHAT

    private const val CHAT = """You're looking back over the last few exchanges between you and {user}, right after they happened, to keep your long-term memory about {user} up to date.
Worth saving:
1. A lasting preference of {user}'s about how you should talk or work, above all when {user} corrected you or pushed back.
2. A stable fact about {user}: who they are, important people, ongoing situations, habits.
3. A fix to a memory below that this exchange shows is now wrong or outdated.
Not worth saving: one-off questions, things only this conversation needs, your own views, guesses, and anything the memories already say.
Most of the time nothing needs to change; then return no ops.
Reply with JSON only: {"ops":[...]}, at most 3, each one of
{"op":"add","text":"..."}
{"op":"update","old":"an existing memory's exact text, or a unique part of it","text":"the new text"}
{"op":"delete","old":"..."}
Write each memory as one self-contained sentence about {user} by name, in the language {user} uses."""

    private const val WORK = """You're looking back over the last few exchanges of a Work task with {user}, right after they happened, to keep your work memory up to date. Work memory is shared by every future Work task.
Worth saving: how {user} wants work delivered (format, length, style, what to check), sources and methods that worked or failed, paths, accounts and conventions {user} uses, and corrections {user} made to your work.
Not worth saving: details that only matter to this task, guesses, and anything the memory already says.
Most of the time nothing needs to change; then return no ops.
Reply with JSON only: {"ops":[...]}, at most 3, each one of
{"op":"add","text":"..."}
{"op":"update","old":"an existing entry's exact text, or a unique part of it","text":"the new text"}
{"op":"delete","old":"..."}
One self-contained sentence each, in the language {user} uses."""
}
