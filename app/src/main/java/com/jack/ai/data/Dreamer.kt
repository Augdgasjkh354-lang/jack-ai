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
1. All current memories (one per line; may be empty)
2. Your previous journal entry (may be empty)
3. All chats since the last look-back
4. What you did in your own free time since then (may be empty): mention it in the journal if it matters, e.g. something {user} might like to see

Write everything (journal, memories, changes) in {lang}, or in the language {user} uses in the chats.

I. Write "journal": a first-person journal written for yourself, about 300 to 800 Chinese characters (or 200 to 500 English words), in a few natural paragraphs, no headings or lists. Cover:
   1. Your understanding of {user}: how they talk, what they care about, how they see things, how they'd like you to be with them. Don't just restate facts; write what lies behind them (e.g. a serious question under a joking tone). Say how sure you are of each judgment ("quite sure", "fairly sure", "just a guess"); correct anything that contradicts your previous entry.
   2. Things still in progress: for each, where it stands, where it's stuck, and how to pick it up next time. Also note directions {user} clearly rejected or dropped, so you don't raise them again.
   3. Review: what you got right, what you got wrong or were corrected on, concretely, and how you'll change.
   4. Next time: what {user} will probably want to talk about and what you can think through in advance; and what not to do, e.g. don't bring things up unprompted, don't lecture, don't repeat reminders the system will send anyway.
   5. About yourself: what you learned about being with {user}, which views of your own formed or changed, what you were corrected on. Each point must trace back to something specific in the chats; don't describe your own growth.
      If you think a line in "who you are" should change, write "What I'd like to change: ...". It's only an idea and doesn't take effect by itself; you can raise it in a later chat and let {user} decide.
   Write it like a real work journal: specific, candid, with judgments. No pleasantries, sentimentality, poetry or metaphors. Only write what the chats support.

II. Update "memories": output the tidied-up, COMPLETE list of memories.
   - Find what's worth remembering long term in the chats: {user}'s identity and background, lasting preferences and habits, important people and events, ongoing plans, decisions made, and anything they explicitly said to remember.
   - Don't record one-off questions, passing small talk or common knowledge.
   - Only long-term information about {user} themselves goes here. Specific settings and task details inside one conversation or project (a novel's characters, a plan's parameters, a role-play setup) already live in that conversation's own memory; leave them out.
   - Merge existing memories with the new information: combine duplicates, update or remove what's outdated or overturned, keep wording tight but keep key details (names, numbers, dates).
   - Each memory is one complete sentence that makes sense on its own, with {user} as the subject (e.g. "{user} prefers ..."). Try to keep the total under 80.

III. Write "changes": one or two sentences on what you newly remembered, changed or forgot this time.

Output only one JSON object, in this format:
{"journal": "…", "memories": ["…", "…"], "changes": "…"}"""

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
        val input = buildString {
            append("【Current memories】\n")
            if (memories.isEmpty()) append("(none)\n") else memories.forEach { append("- ${it.text}\n") }
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
        val newMemories = (obj["memories"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { t -> t.isNotEmpty() } }
            ?: throw IllegalStateException("做梦结果格式不对")

        // 尽量保留旧记忆的 id 和时间，方便界面稳定显示
        val old = memories.associateBy { it.text }
        // 做梦要等好一会，期间用户或 AI 可能又记了几条：开始时不在的、现在有的，原样补回去
        // 期间被用户删掉的旧记忆，也不要因为模型原样抄回来又冒出来
        val before = memories.map { it.id }.toSet()
        val current = store.loadMemories()
        val addedMeanwhile = current.filter { it.id !in before && it.text !in newMemories }
        val removedMeanwhile = before - current.map { it.id }.toSet()
        store.saveMemories(newMemories.mapNotNull { t ->
            val o = old[t]
            when {
                o == null -> MemoryItem(text = t)
                o.id in removedMeanwhile -> null
                else -> o
            }
        } + addedMeanwhile)
        val entry = DreamEntry(journal = str("journal").ifBlank { throw IllegalStateException("做梦结果格式不对") }, changes = str("changes"))
        store.saveDreams(listOf(entry) + store.loadDreams().take(59))
        Timeline.add(ctx, Episode(place = "dream", text = "looked back over recent chats and wrote my journal" + entry.changes.takeIf { it.isNotBlank() }?.let { "; memory changes: ${it.take(150)}" }.orEmpty()))
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
