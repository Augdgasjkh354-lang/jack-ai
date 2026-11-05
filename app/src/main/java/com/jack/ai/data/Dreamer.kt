package com.jack.ai.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.jack.ai.api.DeepSeekClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dream：每天凌晨 5:30 左右，把上次做梦以来的所有聊天整理一遍，
 * 补充进「记忆」，并把全部记忆精简合并（不再写日记）。
 */
object Dreamer {
    private const val PERIODIC = "dream-daily"
    private const val ONCE = "dream-now"
    private const val MORE = "dream-more"
    /** 一次整理最多带多少字的聊天；再多的下一轮接着整理，不丢。 */
    const val TRANSCRIPT_LIMIT = 400_000

    /** 下一个 5:30 距现在多少毫秒。 */
    private fun delayToNext530(): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 5); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    /** 最近一个已经过去的 5:30。 */
    private fun last530(): Long = System.currentTimeMillis() + delayToNext530() - TimeUnit.DAYS.toMillis(1)

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<DreamWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToNext530(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        // 兜底：如果今天 5:30 之后还没做过梦，现在补做
        if (Store(ctx).lastDreamAt < last530()) runNow(ctx)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DreamWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
    }

    /** 聊天太多、这次没整理完：排在这次后面接着整理剩下的。 */
    private fun runMore(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DreamWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(MORE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * 这次要整理的聊天记录：since 之后、until 之前的消息，按时间从早到晚放，放满 limit 字为止（至少放一条），
     * 再按对话分好。返回（整理用的文字，没放完时最后放进来的那条消息的时间；放完了是 null）。
     * 以前是整段拼好再截前 40 万字，聊得多的日子最新的对话会被截掉。
     */
    internal fun transcript(convs: List<Conversation>, since: Long, until: Long, limit: Int = TRANSCRIPT_LIMIT, user: String = "User"): Pair<String, Long?> {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ENGLISH)
        fun line(m: ChatMessage): String {
            val who = if (m.role == "user") user else "Kavi"
            val files = m.attachments.filter { it.kind == "file" }.joinToString("") { "[file: ${it.name}]" }
            val imgs = m.attachments.count { it.kind == "image" }.let { if (it > 0) "[$it image(s)]" else "" }
            return "[${fmt.format(Date(m.createdAt))}] $who: $files$imgs${m.content.take(3000)}\n"
        }
        val all = convs.flatMap { c -> c.messages.filter { it.createdAt in (since + 1)..until && it.content.isNotBlank() }.map { c to it } }
            .sortedBy { it.second.createdAt }
        var used = 0
        var cutoff: Long? = null
        val picked = mutableListOf<Pair<Conversation, ChatMessage>>()
        for ((i, p) in all.withIndex()) {
            val n = line(p.second).length
            if (picked.isNotEmpty() && used + n > limit) {
                // 和最后放进来那条同一毫秒的也放进来，下一轮从这个时间之后接着，不会漏
                cutoff = picked.last().second.createdAt
                picked += all.drop(i).takeWhile { it.second.createdAt == cutoff }
                break
            }
            used += n
            picked += p
        }
        val text = StringBuilder()
        picked.groupBy { it.first.id }.values.forEach { group ->
            text.append("\n\n### Conversation 「${group.first().first.title}」\n")
            group.forEach { text.append(line(it.second)) }
        }
        return text.toString() to cutoff
    }

    private const val INSTRUCTIONS = """It's the end of the day: look back over your recent chats with {user} and tidy your long-term memories about {user}, so they stay accurate, current and within budget.

If there are no new chats, return an empty ops list; don't invent anything.

You'll receive:
1. All current memories, each with a short id like [m:ab12cd], where it came from and how often it has come up lately (may be empty), plus how much of your memory budget they take
   Also deeper memories that surfaced in chats since last time or that these chats touch on
2. All chats since the last look-back

Write memory changes and "changes" in {lang}, or in the language {user} uses in the chats.

I. Write "ops": a list of changes to your memories about {user}. Don't rewrite the list; only say what changes. Memories you don't mention stay exactly as they are.
   - {"op": "add", "text": "...", "why": "...", "source": "..."}: something new worth remembering long term: {user}'s identity and background, lasting preferences and habits, important people and events, ongoing plans, decisions, anything they said to remember.
   - {"op": "update", "id": "ab12cd", "text": "...", "why": "...", "source": "..."}: an existing memory that the chats refine or correct (also use this to fold a duplicate into one, then invalidate the other).
   - {"op": "invalidate", "id": "ab12cd", "why": "...", "source": "..."}: an existing memory the chats clearly overturned, that has passed (a one-off plan whose date is gone or that {user} said is done), or that {user} asked you to forget. It isn't deleted, just retired with its reason.
   - {"op": "merge", "ids": ["ab12cd", "ef34gh"], "text": "...", "why": "..."}: several memories that say the same thing or belong together; they become one sentence.
   - {"op": "demote", "id": "ab12cd", "why": "..."}: still true, but rarely comes up. It moves to your deeper memory: not carried in every chat, but it surfaces by itself when a chat touches it. Nothing is lost.
   - {"op": "promote", "id": "ab12cd", "why": "..."}: a deeper memory that keeps surfacing and matters again; it goes back to the ones you always carry.
   - "source" says where in the chats it comes from, like "10-25 「报价」". Every change needs one; if you can't point to where the chats support it, don't make the change.
   - Be conservative: change only what the chats clearly support. If {user} once said one thing and now another, and you can't tell which holds, keep the old one and add the new one with its date, rather than guessing.
   - When something changed (a new habit replacing an old one), keep how it changed in one memory ("used to …, switched to … because …") rather than only the latest state.
   - Don't record one-off questions, passing small talk, common knowledge, or details that belong to one conversation or project (a novel's characters, a plan's parameters, a role-play setup).
   - If {user} asked you not to remember something, record nothing about it, not even a memory saying it must not be recorded (that memory would repeat it); invalidate any memory that contains it.
   - How {user} likes you to talk and work belongs in one or two memories, not a new one each day: when a chat confirms or refines it, update the existing memory instead of adding another that says nearly the same thing.
   - Each memory is one complete sentence that makes sense on its own, with {user} as the subject.
   - Your context is limited. The memories you always carry share a budget (shown with the list); every chat pays for all of them. Over budget, or close to it, make room: merge duplicates first, then demote what's still true but hasn't come up in a long while; trim long memories to their point. Never invalidate something just to save space: invalidate only what's no longer true. Keep what defines {user} (identity, people who matter, lasting preferences, what they asked you to remember) even if it's rarely used. Under budget, don't tidy for its own sake.
   - "source" isn't needed for merge, demote and promote (they don't change what you know).

II. Write "changes": one or two sentences on what you newly remembered, changed or let go of this time (empty if nothing changed).

Output only one JSON object, in this format:
{"ops": [{"op": "add", "text": "…", "why": "…", "source": "…"}], "changes": "…"}"""

    /**
     * 夜里整理记忆：回看这段时间的聊天，交一张修改单（新增、修改、作废、合并、放到深处、拿回来）。
     * 没有新聊天、没填 Key 时返回 null。
     */
    suspend fun dream(ctx: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): DreamEntry? {
        val store = Store(ctx)
        val settings = store.loadSettings()
        val ep = settings.backgroundEndpoint() ?: return null
        if (!force && !settings.dreamEnabled) return null
        // 从开始整理的这一刻算：整理要好几分钟，期间新聊的下次再整理，不会被跳过
        val startedAt = now
        val since = store.lastDreamAt.takeIf { it > 0 } ?: (startedAt - TimeUnit.DAYS.toMillis(1))
        val projects = store.loadProjects()
        // 关了「参与整理」的对话、隐私项目里的对话不整理
        val convs = store.loadConversations().filter { c -> c.inDream && !c.work && !c.isolatedIn(projects) }
        val (transcript, cutoff) = transcript(convs, since, startedAt, user = Persona.ref(settings) ?: "User")
        if (transcript.isBlank()) {
            store.lastDreamAt = startedAt
            fadeThreads(ctx)
            return null
        }
        val memories = store.loadMemories()
        val held = heldNote(ctx)
        val input = buildString {
            if (held.isNotBlank()) append("【Note】Last time your memory changes were held back: $held Redo the look-back carefully, changing only what the chats clearly support.\n\n")
            append("【Current memories】\n")
            append(MemoryOps.listing(memories))
            append("(memory budget: ~${MemoryOps.hotTokens(memories)} of ${MemoryOps.HOT_BUDGET} tokens used" + (if (MemoryOps.hotTokens(memories) > MemoryOps.HOT_BUDGET) " — over budget, make room" else "") + ")\n")
            val deepNow = MemoryOps.deep(ctx)
            // 浮上来过的，加上和这段聊天沾边的：没浮上来的旧记忆（换了说法、没配向量模型）也能被这次的聊天改掉或作废
            val surfaced = deepNow.filter { it.lastUsedAt > since }.sortedByDescending { it.uses }.take(20)
            val touched = MemoryOps.touching(deepNow, transcript, ignore = MemoryOps.ignoreFor(settings))
            (surfaced + touched).distinctBy { it.id }.takeIf { it.isNotEmpty() }?.let { l ->
                append("\n【Deeper memories that surfaced or that these chats touch on】(${deepNow.size} in deeper memory in all; update or invalidate any the chats show are outdated)\n").append(MemoryOps.listing(l))
            }
            append("\n【Chats since the last look-back】")
            append(transcript)
        }
        val client = DeepSeekClient(store::addUsage)
        val system = "You are Kavi, ${Persona.ref(settings) ?: "the user"}'s assistant. " +
            Persona.nameLine(settings).let { if (it.isEmpty()) "" else "$it\n\n" } + INSTRUCTIONS
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", Persona.fill(system, settings)) },
            buildJsonObject { put("role", "user"); put("content", input) },
        )
        // 偶尔交回来的不是合法 JSON（引号没转义之类）：再问一次，不然这一天的整理就白做了
        val obj = (1..2).firstNotNullOfOrNull { attempt ->
            val raw = client.complete(ep, messages, maxTokens = 16384, jsonMode = true)
            runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }
                .getOrElse { if (attempt == 2) throw it else null }
        }!!
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.content.orEmpty()
        if (obj["ops"] !is JsonArray && obj["ops"] != null) throw IllegalStateException("整理结果格式不对")
        val ops = MemoryOps.parse(obj["ops"] as? JsonArray).filter { !it.own }
        // 一次动到太多已有的记忆：这次整个不算，第二天重新整理（时间点不往后挪）
        if (MemoryOps.tooMany(memories.size, ops)) {
            val n = ops.count { it.op != "add" }
            setHeld(ctx, "the change list touched $n of ${memories.size} memories at once (the limit is ${MemoryOps.limit(memories.size)}).")
            return null
        }
        // 在现在的记忆上执行（整理要好一会，期间用户删掉、改过的照现在的算），改之前存一版
        lateinit var result: MemoryOps.Result
        store.updateMemories { current ->
            MemoryOps.snapshot(ctx, current)
            result = MemoryOps.apply(current, ops, deepNow = MemoryOps.deep(ctx))
            result.live
        }
        if (result.demoted + result.promoted + result.invalidated > 0) MemoryOps.saveDeep(ctx, result.deep)
        MemoryOps.addToArchive(ctx, result.archived)
        setHeld(ctx, "")
        val entry = DreamEntry(journal = "", changes = str("changes"), createdAt = startedAt)
        store.saveDreams(listOf(entry) + store.loadDreams().take(59))
        fadeThreads(ctx)
        // 没整理完的：下次从最后整理到的那条之后接着，马上排一轮
        store.lastDreamAt = cutoff ?: startedAt
        if (cutoff != null) runMore(ctx)
        return entry
    }

    /** 很久没碰的挂心事淡出（不删）。 */
    private fun fadeThreads(ctx: Context) {
        val (faded, gone) = Threads.fade(Threads.load(ctx))
        if (gone.isNotEmpty()) Threads.save(ctx, faded)
    }

    /** 上次做梦被拦下的原因（改动太多）；空 = 没有。 */
    private fun heldFile(ctx: Context) = java.io.File(ctx.filesDir, "dream_held.txt")
    fun heldNote(ctx: Context): String = runCatching { heldFile(ctx).readText() }.getOrDefault("")
    private fun setHeld(ctx: Context, note: String) {
        runCatching { if (note.isBlank()) heldFile(ctx).delete() else heldFile(ctx).writeText(note) }
    }
}

class DreamWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Dreamer.dream(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
