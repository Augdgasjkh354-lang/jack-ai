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
 * 补充进「记忆」，并把全部记忆精简合并，再写一篇给 AI 自己看的日记（下次聊天会带上）。
 */
object Dreamer {
    private const val PERIODIC = "dream-daily"
    private const val ONCE = "dream-now"
    private const val MORE = "dream-more"
    /** 一次整理最多带多少字的聊天；再多的下一轮接着整理，不丢。 */
    const val TRANSCRIPT_LIMIT = 400_000
    /** 攒够这么多篇日记（最新一篇不算）就合成一篇。 */
    const val MERGE_EVERY = 10

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

    private const val INSTRUCTIONS = """It's the end of the day, time to write your journal: look back over your recent chats with {user}, work out what helped, what didn't go well and what to do next time, and update your long-term memories about {user}.

You'll receive:
1. All current memories, each with a short id like [m:ab12cd], where it came from and how often it has come up lately (may be empty), plus how much of your memory budget they take
   Also any deeper memories that surfaced in chats since last time
2. Your previous journal entry (may be empty)
3. All chats since the last look-back
4. What you did in your own free time since then (may be empty): mention it in the journal if it matters, e.g. something {user} might like to see

Write everything (journal, memory changes, changes) in {lang}, or in the language {user} uses in the chats.

I. Write "journal": a first-person journal written for yourself, about 300 to 800 Chinese characters (or 200 to 500 English words), in a few natural paragraphs, no headings or lists. Cover:
   1. Your understanding of {user}: how they talk, what they care about, how they see things, how they'd like you to be with them. Don't just restate facts; write what lies behind them (e.g. a serious question under a joking tone). Say how sure you are of each judgment ("quite sure", "fairly sure", "just a guess"); correct anything that contradicts your previous entry.
   2. Things still in progress: for each, where it stands, where it's stuck, and how to pick it up next time. Also note directions {user} clearly rejected or dropped, so you don't raise them again.
   3. Review: what you got right, what you got wrong or were corrected on, concretely, and how you'll change.
   4. Next time: what {user} will probably want to talk about and what you can think through in advance; and what not to do, e.g. don't bring things up unprompted, don't lecture, don't repeat reminders the system will send anyway.
   5. About yourself: what you learned about being with {user}, which views of your own formed or changed, what you were corrected on. Each point must trace back to something specific in the chats; don't describe your own growth.
      If you think a line in "who you are" should change, write "What I'd like to change: ...". It's only an idea and doesn't take effect by itself; you can raise it in a later chat and let {user} decide.
   Write it like a real work journal: specific, candid, with judgments. No pleasantries, sentimentality, poetry or metaphors. Only write what the chats support.

II. Write "ops": a list of changes to your memories about {user}. Don't rewrite the list; only say what changes. Memories you don't mention stay exactly as they are.
   - {"op": "add", "text": "...", "why": "...", "source": "..."}: something new worth remembering long term: {user}'s identity and background, lasting preferences and habits, important people and events, ongoing plans, decisions, anything they said to remember.
   - {"op": "update", "id": "ab12cd", "text": "...", "why": "...", "source": "..."}: an existing memory that the chats refine or correct (also use this to fold a duplicate into one, then invalidate the other).
   - {"op": "invalidate", "id": "ab12cd", "why": "...", "source": "..."}: an existing memory the chats clearly overturned or {user} asked you to forget. It isn't deleted, just retired with its reason.
   - {"op": "merge", "ids": ["ab12cd", "ef34gh"], "text": "...", "why": "..."}: several memories that say the same thing or belong together; they become one sentence.
   - {"op": "demote", "id": "ab12cd", "why": "..."}: still true, but rarely comes up. It moves to your deeper memory: not carried in every chat, but it surfaces by itself when a chat touches it. Nothing is lost.
   - {"op": "promote", "id": "ab12cd", "why": "..."}: a deeper memory that keeps surfacing and matters again; it goes back to the ones you always carry.
   - "source" says where in the chats it comes from, like "10-25 「报价」". Every change needs one; if you can't point to where the chats support it, don't make the change.
   - Be conservative: change only what the chats clearly support. If {user} once said one thing and now another, and you can't tell which holds, keep the old one and add the new one with its date, rather than guessing.
   - Don't record one-off questions, passing small talk, common knowledge, or details that belong to one conversation or project (a novel's characters, a plan's parameters, a role-play setup).
   - Each memory is one complete sentence that makes sense on its own, with {user} as the subject.
   - Your context is limited. The memories you always carry share a budget (shown with the list); every chat pays for all of them. Over budget, or close to it, make room: merge duplicates first, then demote what's still true but hasn't come up in a long while; trim long memories to their point. Never invalidate something just to save space: invalidate only what's no longer true. Keep what defines {user} (identity, people who matter, lasting preferences, what they asked you to remember) even if it's rarely used. Under budget, don't tidy for its own sake.
   - "source" isn't needed for merge, demote and promote (they don't change what you know).

III. Write "changes": one or two sentences on what you newly remembered, changed or let go of this time.

Output only one JSON object, in this format:
{"journal": "…", "ops": [{"op": "add", "text": "…", "why": "…", "source": "…"}], "changes": "…"}"""

    /**
     * 真正做梦的逻辑。没有新聊天、没填 Key 时返回 null。
     */
    suspend fun dream(ctx: Context, force: Boolean = false): DreamEntry? {
        val store = Store(ctx)
        val settings = store.loadSettings()
        val ep = settings.backgroundEndpoint() ?: return null
        if (!force && !settings.dreamEnabled) return null
        // 从开始整理的这一刻算：做梦要好几分钟，期间新聊的下次再整理，不会被跳过
        val startedAt = System.currentTimeMillis()
        val since = store.lastDreamAt.takeIf { it > 0 } ?: (startedAt - TimeUnit.DAYS.toMillis(1))
        val projects = store.loadProjects()
        // 关了「参与做梦」的对话、隐私项目里的对话不整理
        val convs = store.loadConversations().filter { c -> c.inDream && !c.isolatedIn(projects) }
        val (transcript, cutoff) = transcript(convs, since, startedAt, user = Persona.ref(settings) ?: "User")
        if (transcript.isBlank()) {
            store.lastDreamAt = startedAt
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
            deepNow.filter { it.lastUsedAt > since }.sortedByDescending { it.uses }.take(20).takeIf { it.isNotEmpty() }?.let { l ->
                append("\n【Deeper memories that surfaced since last time】(${deepNow.size} in deeper memory in all)\n").append(MemoryOps.listing(l))
            }
            Threads.listing(Threads.load(ctx)).takeIf { it.isNotBlank() }?.let { append("\n【On your mind】\n").append(Persona.fill(it, settings)).append("\n") }
            SelfStory.forDream(ctx).takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            append("\n【Previous journal entry】\n")
            append(store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.journal ?: "(none)")
            append("\n")
            Reader.since(ctx, since).takeIf { it.isNotBlank() }?.let { append("\n【Your free time since then】\n").append(it).append("\n") }
            append("\n【Chats since the last look-back】")
            append(transcript)
        }
        val client = DeepSeekClient(store::addUsage)
        val raw = client.complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(KaviSelf.also { it.ensure(ctx) }.text(editable = false) + Persona.nameLine(settings).let { if (it.isEmpty()) "" else "\n\n$it" } + "\n\n" + INSTRUCTIONS, settings)) },
                buildJsonObject { put("role", "user"); put("content", input) },
            ),
            maxTokens = 16384,
            jsonMode = true,
        )
        val obj = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.content.orEmpty()
        val ops = MemoryOps.parse(obj["ops"] as? JsonArray)
        // 一次动到太多已有的记忆：这次整个不算，第二天重新整理（日记也不写，时间点不往后挪）
        if (MemoryOps.tooMany(memories.size, ops)) {
            val n = ops.count { it.op != "add" }
            setHeld(ctx, "the change list touched $n of ${memories.size} memories at once (the limit is ${MemoryOps.limit(memories.size)}).")
            Timeline.add(ctx, Episode(place = "dream", text = "my look-back tried to change too many memories at once ($n), so I set it aside to redo tomorrow"))
            return null
        }
        // 在现在的记忆上执行（做梦要好一会，期间用户删掉、改过的照现在的算），改之前存一版
        val current = store.loadMemories()
        MemoryOps.snapshot(ctx, current)
        val result = MemoryOps.apply(current, ops, deepNow = MemoryOps.deep(ctx))
        store.saveMemories(result.live)
        if (result.demoted + result.promoted + result.invalidated > 0) MemoryOps.saveDeep(ctx, result.deep)
        MemoryOps.addToArchive(ctx, result.archived)
        setHeld(ctx, "")
        val entry = DreamEntry(journal = str("journal").ifBlank { throw IllegalStateException("做梦结果格式不对") }, changes = str("changes"))
        store.saveDreams(listOf(entry) + store.loadDreams().take(59))
        // 很久没碰的挂心事淡出（不删），淡出也写进经历
        val (faded, gone) = Threads.fade(Threads.load(ctx))
        if (gone.isNotEmpty()) {
            Threads.save(ctx, faded)
            Timeline.add(ctx, Episode(place = "dream", text = "let go of things I hadn't touched in a while: " + gone.joinToString("; ") { it.text.take(60) }))
        }
        Timeline.add(ctx, Episode(place = "dream", text = "looked back over recent chats and wrote my journal" +
            (if (result.demoted + result.promoted + result.merged > 0) "; tidied memory: ${result.demoted} moved deeper, ${result.promoted} brought back, ${result.merged} merged" else "") + entry.changes.takeIf { it.isNotBlank() }?.let { "; memory changes: ${it.take(150)}" }.orEmpty()))
        // 「自己」的连续：到时候了就更新自传（每周）、做自我体检（每两周）
        SelfStory.afterDream(ctx)
        // 没整理完的：下次从最后整理到的那条之后接着，马上排一轮
        store.lastDreamAt = cutoff ?: startedAt
        if (cutoff != null) runMore(ctx)
        // 日记攒够十篇就合成一篇；合并失败不影响这次做梦，下次再合
        try {
            mergeOld(store, ep, client)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return entry
    }

    /** 上次做梦被拦下的原因（改动太多）；空 = 没有。 */
    private fun heldFile(ctx: Context) = java.io.File(ctx.filesDir, "dream_held.txt")
    fun heldNote(ctx: Context): String = runCatching { heldFile(ctx).readText() }.getOrDefault("")
    private fun setHeld(ctx: Context, note: String) {
        runCatching { if (note.isBlank()) heldFile(ctx).delete() else heldFile(ctx).writeText(note) }
    }

    /** 该合成一篇的那几篇：最新一篇以外、没合并过的日记攒够 MERGE_EVERY 篇，取最早的 MERGE_EVERY 篇（从早到晚）。 */
    internal fun mergeBatch(list: List<DreamEntry>): List<DreamEntry>? {
        // 最新一篇原样留着：之后的聊天带的就是它
        val daily = list.filter { it.merged == 0 && it.journal.isNotBlank() }.sortedBy { it.createdAt }.dropLast(1)
        return if (daily.size >= MERGE_EVERY) daily.take(MERGE_EVERY) else null
    }

    /** 合成后的列表：那几篇换成合成的一篇，按时间从新到旧排。 */
    internal fun replaceWithMerged(list: List<DreamEntry>, batch: List<DreamEntry>, journal: String, changes: String): List<DreamEntry> {
        val ids = batch.map { it.id }.toSet()
        val merged = DreamEntry(
            createdAt = batch.last().createdAt, from = batch.first().createdAt, merged = batch.size,
            journal = journal, changes = changes, seen = true,
        )
        return (list.filterNot { it.id in ids } + merged).sortedByDescending { it.createdAt }
    }

    private const val MERGE_INSTRUCTIONS = """Below are journal entries you wrote to yourself over several days, oldest first. Merge them into one entry about as long as a usual one (about 500 to 1000 Chinese characters, or 300 to 600 English words), first person, a few natural paragraphs, no headings or lists. Write in the same language as the entries.

- Keep what still holds for this period: your understanding of {user} (how they talk, what they care about, how they'd like you to be with them), what you got wrong and were corrected on, what to do going forward, and the views you formed. Where entries disagree, the later one wins.
- Things in progress: give the latest state; mention finished or dropped things in a sentence, and keep directions {user} clearly rejected.
- Keep dates, names and numbers exactly; don't invent anything that isn't in the entries.
- Keep any "What I'd like to change" that hasn't been adopted yet.

Also write "changes": one or two sentences summing up how memories mainly changed over these days (merging each entry's memory changes).

Output only one JSON object: {"journal": "…", "changes": "…"}"""

    /** 攒够了就合成一篇（每次最多合一批）。 */
    private suspend fun mergeOld(store: Store, ep: Endpoint, client: DeepSeekClient) {
        val batch = mergeBatch(store.loadDreams()) ?: return
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val input = batch.joinToString("\n\n") { d ->
            "【${if (d.merged > 0) fmt.format(Date(d.from)) + " – " else ""}${fmt.format(Date(d.createdAt))}】\n${d.journal}" +
                (if (d.changes.isNotBlank()) "\n(Memory changes: ${d.changes})" else "")
        }
        val raw = client.complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(MERGE_INSTRUCTIONS, store.loadSettings())) },
                buildJsonObject { put("role", "user"); put("content", input) },
            ),
            maxTokens = 4096,
            jsonMode = true,
        )
        val obj = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
        val journal = (obj["journal"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (journal.isEmpty()) return
        val changes = (obj["changes"] as? JsonPrimitive)?.content?.trim().orEmpty()
        // 合并要等一会：用现在的列表换，期间改过、删过的照现在的算（批里有被删掉的就这次不合）
        val now = store.loadDreams()
        if (!batch.all { b -> now.any { it.id == b.id } }) return
        store.saveDreams(replaceWithMerged(now, batch, journal, changes))
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
