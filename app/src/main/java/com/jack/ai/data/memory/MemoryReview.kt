package com.jack.ai.data.memory

import android.content.Context
import com.jack.ai.api.DeepSeekClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.INBOX_ID
import com.jack.ai.data.MemEdit
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Persona
import com.jack.ai.data.Project
import com.jack.ai.data.Retired
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.workMemoryAllowed

/**
 * 聊着聊着就回看一下（借鉴 Hermes Agent 每轮之后的后台复盘）：每 [EVERY] 轮，用后台模型看看刚聊的几轮，
 * 有没有值得长期记住的（用户的偏好、纠正 Kavi 的话、关于他的稳定事实、哪条旧记忆说错了），有就记下。
 * 平常聊天记进全局记忆，做任务时记进做事经验。夜里做梦整理全天的，这个补上白天的空档：纠正过的话当场记住。
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

    /** scope：项目对话里的回看分「project」（项目记忆）和「global」（全局记忆）；别处是空的。 */
    data class Op(val op: String, val text: String = "", val old: String = "", val scope: String = "")

    /** 项目对话一次最多改几条（要顺手整理进度、删掉做完的待办，比别处多一些）。 */
    const val PROJECT_MAX_OPS = 6

    fun parse(raw: String, max: Int = MAX_OPS): List<Op> {
        val obj = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull() ?: return emptyList()
        return (obj["ops"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            val op = s("op").lowercase()
            val scope = s("scope").lowercase()
            when {
                op == "add" && s("text").isNotEmpty() -> Op("add", s("text"), scope = scope)
                op == "update" && s("old").isNotEmpty() && s("text").isNotEmpty() -> Op("update", s("text"), s("old"), scope)
                op == "delete" && s("old").isNotEmpty() -> Op("delete", old = s("old"), scope = scope)
                else -> null
            }
        }.take(max)
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

    /**
     * 和 apply 一样，但直接在带 id 的记忆上改：改过的条目保留 id、历史、用量和来源，
     * 不会因为先转成字符串、再按文字拼回去而丢掉身份，同文字的多条也不会被合并成一条。
     * 工作记忆是纯字符串文件，继续用 apply；全局记忆（带 id）用这个。
     */
    fun applyItems(items: List<MemoryItem>, ops: List<Op>, budget: Int, source: String, now: Long = System.currentTimeMillis()): Pair<List<MemoryItem>, List<String>> {
        var list = items
        val done = mutableListOf<String>()
        fun match(old: String): Int {
            val key = old.trim().removePrefix("- ").trim()
            if (key.isEmpty()) throw IllegalArgumentException("replace 要填旧记忆的原话")
            val exact = list.indices.filter { list[it].text == key }
            val hits = exact.ifEmpty { list.indices.filter { list[it].text.contains(key, ignoreCase = true) } }
            if (hits.size != 1) throw IllegalArgumentException("「$key」对上了 ${hits.size} 条")
            return hits.single()
        }
        for (op in ops) {
            val next = runCatching {
                when (op.op) {
                    "add" -> if (list.any { it.text == op.text }) null else list + MemoryItem(text = op.text, createdAt = now, source = source)
                    "update" -> {
                        val i = match(op.old)
                        val m = list[i]
                        list.mapIndexed { j, v ->
                            if (j == i) v.copy(text = op.text, history = m.history + MemEdit(now, m.text, op.text, "聊天时回看", source)) else v
                        }
                    }
                    else -> {
                        val i = match(op.old)
                        list.filterIndexed { j, _ -> j != i }
                    }
                }
            }.getOrNull() ?: continue
            if (MemoryOps.fullNote(list.map { it.text }, next.map { it.text }, budget, "") != null) continue
            list = next
            done += when (op.op) { "add" -> "记下：${op.text}"; "update" -> "改成：${op.text}"; else -> "删掉：${op.old}" }
        }
        return list to done
    }


    /** 顺手记执行结果；界面负责刷新状态和显示提示，考场负责保存回看位置。project：项目记忆改了哪些（全局的在 done 里）。 */
    data class ReviewResult(val done: List<String>, val reviewedAt: Long, val memories: List<MemoryItem>?, val work: Boolean,
        val project: List<String> = emptyList())

    /**
     * App 与记忆考场共用同一条真实回看路径。
     * 返回 null 表示本轮不该回看或没有可用模型；请求失败由调用方处理。
     */
    suspend fun review(
        ctx: Context, store: Store, conv: Conversation, project: Project?,
        settings: Settings, client: DeepSeekClient,
        /** 离开项目里的对话时交接：没攒够几轮也看（有一条没看过的用户消息就行）。 */
        handoff: Boolean = false,
    ): ReviewResult? {
        val ready = due(conv) || (handoff && project != null && unseen(conv).any { it.role == "user" })
        if (!ready || !settings.memoryEnabled || !conv.inDream || conv.ownLife ||
            conv.id == INBOX_ID || conv.id == Retired.FREE_TIME_CONV) return null
        if (project != null) return reviewProject(ctx, store, conv, project, settings, client)
        val unseen = unseen(conv)
        val work = unseen.any { it.plan.isNotEmpty() } && conv.workMemoryAllowed(project)
        val chat = !work && conv.globalAllowed(project)
        if (!work && !chat) return null
        val ep = settings.backgroundEndpoint() ?: return null
        val workFile = ChatMemory.workFile(ctx)
        val budget = if (work) MemoryOps.FILE_BUDGET else MemoryOps.HOT_BUDGET
        val before = if (work) ChatMemory.items(ChatMemory.read(workFile)) else store.loadMemories().map { it.text }
        val input = "【Current ${if (work) "work memory" else "memories"}】(~${MemoryOps.tokensOf(before)} of $budget tokens)\n" +
            before.ifEmpty { listOf("(none)") }.joinToString("\n") { "- $it" } +
            "\n\n【The latest exchanges】\n" + transcript(unseen, Persona.ref(settings) ?: "User")
        val raw = client.complete(ep, listOf(
            buildJsonObject {
                put("role", "system")
                put("content", Persona.fill(instructions(work) + Persona.nameLine(settings).let {
                    if (it.isEmpty()) "" else "\n\n$it"
                }, settings))
            },
            buildJsonObject { put("role", "user"); put("content", input) },
        ), maxTokens = 800, jsonMode = true)
        val ops = parse(raw)
        var done: List<String> = emptyList()
        var saved: List<MemoryItem>? = null
        if (work) {
            ChatMemory.update(workFile) { live ->
                val (next, changes) = apply(live, ops, budget)
                done = changes
                next.takeIf { changes.isNotEmpty() }
            }
        } else {
            store.updateMemories { live ->
                val (next, changes) = applyItems(live, ops, budget, "聊天时回看：${conv.title}")
                done = changes
                if (changes.isEmpty()) null else {
                    MemoryOps.snapshot(ctx, live)
                    saved = next
                    next
                }
            }
        }
        return ReviewResult(done, unseen.maxOf { it.createdAt }, saved, work)
    }

    /**
     * 项目里的对话：一次请求同时管项目记忆（设定、决定、进度、待办）和全局记忆（隐私项目、关了全局记忆的不管）。
     * 新对话开头带着项目记忆，所以这里记好了，下次在项目里开新对话就能接着做。
     */
    private suspend fun reviewProject(
        ctx: Context, store: Store, conv: Conversation, project: Project, settings: Settings, client: DeepSeekClient,
    ): ReviewResult? {
        val ep = settings.backgroundEndpoint() ?: return null
        val unseen = unseen(conv)
        val globalOn = conv.globalAllowed(project)
        val file = ChatMemory.projectFile(ctx, project.id)
        val before = ChatMemory.items(ChatMemory.read(file))
        val globals = if (globalOn) store.loadMemories().map { it.text } else emptyList()
        val input = buildString {
            append("【Project】${project.name}\n")
            project.instructions.trim().takeIf { it.isNotEmpty() }?.let { append(it.take(800)).append("\n") }
            append("\n【Current project memory】(~${MemoryOps.tokensOf(before)} of ${MemoryOps.PROJECT_BUDGET} tokens)\n")
            append(before.ifEmpty { listOf("(none)") }.joinToString("\n") { "- $it" })
            if (globalOn) {
                append("\n\n【Current global memories】(~${MemoryOps.tokensOf(globals)} of ${MemoryOps.HOT_BUDGET} tokens)\n")
                append(globals.ifEmpty { listOf("(none)") }.joinToString("\n") { "- $it" })
            }
            append("\n\n【The latest exchanges in conversation 「${conv.title}」】\n")
            append(transcript(unseen, Persona.ref(settings) ?: "User"))
        }
        val raw = client.complete(ep, listOf(
            buildJsonObject {
                put("role", "system")
                put("content", Persona.fill(PROJECT + (if (globalOn) PROJECT_GLOBAL else PROJECT_NO_GLOBAL) +
                    Persona.nameLine(settings).let { if (it.isEmpty()) "" else "\n\n$it" }, settings))
            },
            buildJsonObject { put("role", "user"); put("content", input) },
        ), maxTokens = 1200, jsonMode = true)
        val ops = parse(raw, PROJECT_MAX_OPS)
        var projectDone: List<String> = emptyList()
        ChatMemory.update(file) { live ->
            val (next, changes) = apply(live, keepSections(live, ops.filter { it.scope != "global" }), MemoryOps.PROJECT_BUDGET)
            projectDone = changes
            next.takeIf { changes.isNotEmpty() }
        }
        var done: List<String> = emptyList()
        var saved: List<MemoryItem>? = null
        val globalOps = ops.filter { it.scope == "global" }.take(MAX_OPS)
        if (globalOn && globalOps.isNotEmpty()) store.updateMemories { live ->
            val (next, changes) = applyItems(live, globalOps, MemoryOps.HOT_BUDGET, "聊天时回看：${conv.title}")
            done = changes
            if (changes.isEmpty()) null else {
                MemoryOps.snapshot(ctx, live)
                saved = next
                next
            }
        }
        return ReviewResult(done, unseen.maxOf { it.createdAt }, saved, work = false, project = projectDone)
    }

    /** 改一条时新写法忘了带块标记：沿用原来那条的（不然会从「进度」掉到「其他」里）。 */
    internal fun keepSections(live: List<String>, ops: List<Op>): List<Op> = ops.map { op ->
        if (op.op != "update" || ChatMemory.sectionOf(op.text) != null) op
        else {
            val key = op.old.trim().removePrefix("- ").trim()
            val hit = live.firstOrNull { it == key } ?: live.singleOrNull { it.contains(key, ignoreCase = true) }
            hit?.let { ChatMemory.sectionOf(it) }?.let { op.copy(text = "【$it】" + op.text) } ?: op
        }
    }

    /** 给用户看的那条说明（挂在回答下面）。 */
    fun notice(done: List<String>, work: Boolean): String =
        (if (work) "顺手更新了做事经验" else "顺手更新了记忆") + "\n" + done.joinToString("\n")

    fun projectNotice(done: List<String>): String = "顺手更新了项目记忆\n" + done.joinToString("\n")

    const val NOTICE_PREFIX = "顺手更新了"

    fun instructions(work: Boolean): String = if (work) WORK else CHAT

    private const val CHAT = """You're looking back over the last few exchanges between you and {user}, right after they happened, to keep your long-term memory about {user} up to date.
Worth saving:
1. A lasting preference of {user}'s about how you should talk or work, above all when {user} corrected you or pushed back.
2. A stable fact about {user}: who they are, important people, ongoing situations, habits.
3. A fix to a memory below that this exchange shows is now wrong or outdated.
Not worth saving: one-off questions, things only this conversation needs, your own views, guesses, and anything the memories already say.
If {user} asks you not to remember something, don't record it in any form, not even a memory saying it must not be recorded, since that memory would itself repeat it; if a memory below already contains it, delete that memory.
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

    private const val PROJECT = """You're looking back over the latest exchanges in one conversation of a project with {user}, to keep the project memory current. Every new conversation in this project starts with the project memory and nothing else from the old conversations, so it must let you pick up exactly where things stand.
Project memory has four sections; start every entry with its tag:
【设定】 lasting background: what the project is, characters, audience, style, constraints, materials and where they are.
【决定】 things {user} decided or agreed on, with the reason when it matters.
【进度】 what's done and where things stand now (finished chapters, files made, current step). Keep it to a few entries: update the existing 【进度】 entry instead of adding another.
【待办】 concrete open items: next steps, unanswered questions, things {user} said to do later. Delete one once it's done.
Keep it tidy as you go: merge entries about the same thing, correct entries these exchanges show are wrong or outdated, and when it's close to the limit, shorten or remove what no longer matters before adding.
Not worth saving: chit-chat, one-off questions, your own guesses, and details only this conversation needs.
If nothing changed, return no ops."""

    private const val PROJECT_GLOBAL = """
Separately, if these exchanges show a lasting fact or preference about {user} themselves (not about the project), you may also update global memory, at most 3 ops; usually none.
Reply with JSON only: {"ops":[...]}, at most 6, each one of
{"scope":"project","op":"add","text":"【进度】..."}
{"scope":"project","op":"update","old":"an existing entry's exact text, or a unique part of it","text":"【进度】the new text"}
{"scope":"project","op":"delete","old":"..."}
or the same with "scope":"global" (no section tag; one sentence about {user} by name).
One self-contained sentence per entry, in the language {user} uses."""

    private const val PROJECT_NO_GLOBAL = """
Reply with JSON only: {"ops":[...]}, at most 6, each one of
{"scope":"project","op":"add","text":"【进度】..."}
{"scope":"project","op":"update","old":"an existing entry's exact text, or a unique part of it","text":"【进度】the new text"}
{"scope":"project","op":"delete","old":"..."}
One self-contained sentence per entry, in the language {user} uses."""
}
