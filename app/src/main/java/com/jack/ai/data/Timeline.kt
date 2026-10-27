package com.jack.ai.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一段经历：什么时候、在哪（chat 聊天 / free 自由时间 / dream 做梦 / proactive 主动找用户）、哪个对话、发生了什么、用户当时怎么样。
 */
@Serializable
data class Episode(
    val at: Long = System.currentTimeMillis(),
    val place: String,
    val convId: String = "",
    val title: String = "",
    val text: String,
    val mood: String = "",
)

/**
 * 经历流：Kavi 的经历都写进这一条线（聊天、自由时间、做梦、主动找用户），只往后追加，从不改写。
 * 不管在哪醒来，它都知道自己最近经历了什么、离上次见用户过了多久（[note]，放在每轮的附加说明里，不影响缓存）。
 * 隐私项目、关了全局记忆的对话不进这条线。
 * 聊天的经历：一段聊天停下 [IDLE_MIN] 分钟后在后台用便宜模型写一两句摘要；还没写的，先用最后几句话顶上。
 */
object Timeline {
    const val IDLE_MIN = 15L
    /** 「刚才」带几段。 */
    const val RECENT = 5
    /** 隔这么多天以上，才可以提一句「隔了几天了」。 */
    const val GAP_DAYS = 3
    private const val KEEP = 3000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "timeline.jsonl")
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "timeline_state.json")

    fun load(ctx: Context): List<Episode> = synchronized(lock) {
        runCatching { file(ctx).readLines() }.getOrDefault(emptyList())
            .mapNotNull { runCatching { json.decodeFromString(Episode.serializer(), it) }.getOrNull() }
    }

    fun add(ctx: Context, e: Episode) = synchronized(lock) {
        val f = file(ctx)
        runCatching { f.appendText(json.encodeToString(Episode.serializer(), e) + "\n") }
        // 太长了就只留最近的（更早的已经进了日记和记忆）
        if (f.length() > 2_000_000) runCatching {
            val lines = f.readLines().takeLast(KEEP)
            f.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** 每个对话摘要到了哪条消息（时间）。 */
    private fun summarizedUpTo(ctx: Context): Map<String, Long> = synchronized(lock) {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), stateFile(ctx).readText()) }.getOrDefault(emptyMap())
    }

    private fun markSummarized(ctx: Context, convId: String, at: Long) = synchronized(lock) {
        val m = summarizedUpTo(ctx) + (convId to at)
        runCatching { stateFile(ctx).writeText(json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), m)) }
    }

    /** 这个对话进不进经历流：隐私项目、关了全局记忆的、自由时间和收件箱自己的不进。 */
    fun counts(c: Conversation, projects: List<Project>): Boolean =
        c.id != Reader.CONV_ID && c.id != INBOX_ID && c.globalAllowed(projects.firstOrNull { it.id == c.projectId })

    // ---------- 给模型看的 ----------

    /** 「20 minutes ago」「yesterday」这样的相对时间。 */
    fun ago(at: Long, now: Long): String {
        val m = (now - at) / 60_000
        return when {
            m < 2 -> "just now"
            m < 60 -> "$m minutes ago"
            m < 24 * 60 -> "${m / 60} hours ago"
            m < 48 * 60 -> "yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    private fun placeName(e: Episode) = when (e.place) {
        "chat" -> "chat 「${e.title}」"
        "free" -> "your free time"
        "dream" -> "your nightly look-back"
        "proactive" -> "you reached out to {user}"
        else -> e.place
    }

    /**
     * 每轮附加的那段：最近几段经历（不分场合，这个对话自己的不算，聊天记录里已经有了）、
     * 还没写摘要的别的对话（最后几句顶上）、上次和用户说话是什么时候、这期间自己做了什么。
     */
    fun note(
        episodes: List<Episode>,
        convs: List<Conversation>,
        projects: List<Project>,
        current: Conversation,
        summarized: Map<String, Long>,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (!counts(current, projects)) return ""
        // 别的对话里，摘要之后又聊了的：先用最后几句话顶上
        val pending = convs.filter { it.id != current.id && counts(it, projects) }.mapNotNull { c ->
            val since = summarized[c.id] ?: 0L
            val fresh = c.messages.filter { it.createdAt > since && it.content.isNotBlank() }
            if (fresh.none { it.role == "user" }) return@mapNotNull null
            val user = fresh.lastOrNull { it.role == "user" }?.content
            val reply = fresh.lastOrNull { it.role == "assistant" }?.content
            Episode(at = fresh.last().createdAt, place = "chat", convId = c.id, title = c.title,
                text = (user?.let { "{user} said “${Recap.clip(it, 60)}”" } ?: "") + (reply?.let { "; you answered “${Recap.clip(it, 60)}”" } ?: ""))
        }
        val recent = (episodes.filter { it.convId != current.id || it.place != "chat" } + pending)
            .filter { now - it.at < 14 * 86_400_000L }
            .sortedBy { it.at }.takeLast(RECENT)
        // 上次和用户说话：所有算数的对话里，这条消息之前最后一条用户的话
        val currentUserAt = current.messages.lastOrNull { it.role == "user" }?.createdAt ?: now
        val lastTalk = convs.filter { counts(it, projects) }.flatMap { c -> c.messages.filter { it.role == "user" && it.createdAt < currentUserAt - 1000 }.map { c to it.createdAt } }
            .maxByOrNull { it.second }
        val out = StringBuilder()
        if (recent.isNotEmpty()) {
            out.append("【Recently】(your own memory across conversations and your own time, oldest first)\n")
            recent.forEach { e ->
                out.append("- ").append(ago(e.at, now)).append(", ").append(placeName(e)).append(": ").append(Recap.clip(e.text, 160))
                if (e.mood.isNotBlank()) out.append(" ({user} seemed ").append(Recap.clip(e.mood, 30)).append(")")
                out.append("\n")
            }
        }
        if (lastTalk != null) {
            val gapDays = (currentUserAt - lastTalk.second) / 86_400_000L
            out.append("【Time】Before this message, {user} last talked with you ").append(ago(lastTalk.second, currentUserAt))
                .append(" in 「").append(lastTalk.first.title).append("」.")
            val mine = episodes.filter { it.at > lastTalk.second && it.place in setOf("free", "dream") }
            if (mine.isNotEmpty()) out.append(" Since then, on your side: ").append(mine.groupingBy { it.place }.eachCount().entries.joinToString(", ") { (p, n) ->
                (if (p == "free") "free time" else "nightly look-back") + if (n > 1) " ×$n" else "" }).append(".")
            out.append(if (gapDays >= GAP_DAYS) " It's been a while: you may acknowledge it briefly and naturally (no guilt-tripping)." else " Don't comment on the time gap.")
            out.append("\n")
        }
        return out.toString()
    }

    /** 在 App 里组好每轮那段（对话和项目用内存里的，经历流读文件，放后台线程调）。 */
    fun noteFor(ctx: Context, settings: Settings, convs: List<Conversation>, projects: List<Project>, conv: Conversation): String {
        val n = note(load(ctx), convs, projects, conv, summarizedUpTo(ctx))
        if (n.isEmpty() && !counts(conv, projects)) return ""
        return Persona.fill(n + Threads.note(Threads.load(ctx)), settings)
    }

    // ---------- 聊天的摘要 ----------

    /** 一段聊天回答完：等它停下 IDLE_MIN 分钟再写摘要（又聊了就往后推）。 */
    fun scheduleSummary(ctx: Context, convId: String) {
        val req = OneTimeWorkRequestBuilder<TimelineWorker>()
            .setInitialDelay(IDLE_MIN, TimeUnit.MINUTES)
            .setInputData(workDataOf("conv" to convId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("timeline-$convId", ExistingWorkPolicy.REPLACE, req) }
    }

    private const val SUMMARY_RULES = """You are writing one entry in your own timeline: a memory of a conversation you just had with {user}. You'll get the messages since your last entry for this conversation, and the things currently on your mind (each with an id like [t:ab12cd]).
Write in {lang}. Output only one JSON object:
{"text": "1 or 2 sentences: what happened and what matters for later (decisions, how it ended). Refer to {user} by name and to yourself as I.",
 "mood": "how {user} seemed, a few words; empty if nothing stood out",
 "open": [{"text": "something now on your mind because of this conversation", "kind": "promise | unfinished | wondering | looking_forward"}],
 "closed": [{"id": "ab12cd", "how": "how it got settled"}],
 "touched": ["ab12cd"]}
- open: only real ones: things you promised {user}, a topic left halfway, a question you genuinely want to keep thinking about, something you're looking forward to. Usually zero or one; never more than three.
- closed: things on your mind that this conversation settled (done, answered, dropped).
- touched: things on your mind that came up again but aren't settled."""

    /** 把这个对话上次摘要之后的消息写成一段经历。没有新的用户消息就不写。 */
    suspend fun summarize(ctx: Context, convId: String) {
        val store = Store(ctx)
        val s = store.loadSettings()
        val conv = store.loadConversations().firstOrNull { it.id == convId } ?: return
        if (!counts(conv, store.loadProjects())) return
        val since = summarizedUpTo(ctx)[convId] ?: 0L
        val fresh = conv.messages.filter { it.createdAt > since && it.content.isNotBlank() && it.error == null }
        if (fresh.none { it.role == "user" }) return
        val ep = s.backgroundEndpoint() ?: return
        val who = Persona.ref(s) ?: "User"
        val transcript = fresh.joinToString("\n") { m -> (if (m.role == "user") who else "Kavi") + ": " + Recap.clip(m.content, 600) }.takeLast(8000)
        val threads = Threads.load(ctx)
        val raw = com.jack.ai.api.DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(SUMMARY_RULES, s)) },
                buildJsonObject { put("role", "user"); put("content", "On your mind:\n" + Persona.fill(Threads.listing(threads), s).ifBlank { "(nothing)" } + "\n\nConversation 「${conv.title}」:\n$transcript") },
            ),
            maxTokens = 400,
            jsonMode = true,
        )
        val o = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull() ?: return
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
        val text = o.str("text").ifBlank { return }
        add(ctx, Episode(at = fresh.last().createdAt, place = "chat", convId = conv.id, title = conv.title, text = text.take(400), mood = o.str("mood").take(60)))
        // 挂着的事：这段聊天新挂上的、了结的、又碰到的
        Threads.save(ctx, Threads.applyJson(Threads.load(ctx), o, conv.id))
        markSummarized(ctx, convId, fresh.last().createdAt)
    }
}

class TimelineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        inputData.getString("conv")?.let { Timeline.summarize(applicationContext, it) }
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
