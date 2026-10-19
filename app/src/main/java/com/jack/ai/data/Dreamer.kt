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

    private const val INSTRUCTIONS = """你是用户的私人 AI 助手。现在是一天结束后你给自己写日记的时间：回看这段时间和用户的聊天，想清楚什么帮上了忙、什么没做好、下次该怎么做，并更新对用户的长期记忆。

你会收到：
1. 当前的全部记忆（每条一行，可能为空）
2. 你上一篇日记（可能为空）
3. 上次整理以来的所有聊天记录

一、写“journal”：第一人称、写给你自己看的日记，300 到 800 字，分几个自然段，不用标题和列表。写这几样：
   1. 对用户的理解：从聊天里看出来的说话方式、在意什么、怎么看问题、希望你怎么跟他相处。不要只复述事实，要写出背后的意思（比如玩笑语气下其实是个认真的问题）。每个判断说清把握多大（“相当有把握”“把握中等”“只是猜测”）；和上一篇日记对不上的，直接修正。
   2. 眼下还在进行的事：每件写清进展到哪、卡在哪、下次怎么接上。用户明确拒绝或放弃的方向也写下来，以后别再提。
   3. 复盘：哪里做对了，哪里做错了或被用户纠正了，具体到事，说清以后怎么改。
   4. 下次怎么做：用户回来大概会聊什么、你可以提前想好什么；以及哪些事不该做，比如他没提就别主动提起、别说教、别重复系统已经会发的提醒。
   写得像真实的工作日记：具体、坦诚、有判断。不客套、不煽情、不写诗、不打比方。只写聊天里有依据的东西。

二、更新“memories”：输出整理后的【完整】记忆列表。
   - 从聊天中找出值得长期记住的信息：用户的身份背景、长期偏好和习惯、重要的人和事、正在进行的计划、做过的决定、明确说“记住”的内容。
   - 一次性的问题、临时的闲聊、常识性内容不要记。
   - 这里只放关于用户本人的长期信息。某个对话或项目里的具体设定和任务细节（比如小说的人物设定、某个方案的参数、角色扮演的设定）已经存在那个对话自己的记忆里，不要放进这里。
   - 把已有记忆和新信息合并：重复的合成一条，过时或被推翻的更新或删掉，表述精简但保留关键细节（名字、数字、日期）。
   - 每条记忆是一句完整、独立能看懂的话，用“用户……”开头描述用户，总数尽量不超过 80 条。

三、写“changes”：一两句话说明这次新记住了什么、改了什么、忘掉了什么。

只输出一个 JSON 对象，格式：
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
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        val transcript = StringBuilder()
        store.loadConversations()
            // 关了「参与做梦」的对话不整理
            .filter { c -> c.inDream && c.messages.any { it.createdAt > since } }
            .sortedBy { c -> c.messages.first { it.createdAt > since }.createdAt }
            .forEach { c ->
                transcript.append("\n\n### 对话「${c.title}」\n")
                c.messages.filter { it.createdAt > since && it.content.isNotBlank() }.forEach { m ->
                    val who = if (m.role == "user") "用户" else "AI"
                    val files = m.attachments.filter { it.kind == "file" }.joinToString("") { "[文件：${it.name}]" }
                    val imgs = m.attachments.count { it.kind == "image" }.let { if (it > 0) "[$it 张图片]" else "" }
                    transcript.append("[${fmt.format(Date(m.createdAt))}] $who：$files$imgs${m.content.take(3000)}\n")
                }
            }
        if (transcript.isBlank()) {
            store.lastDreamAt = startedAt
            return null
        }
        val memories = store.loadMemories()
        val input = buildString {
            append("【当前记忆】\n")
            if (memories.isEmpty()) append("（暂无）\n") else memories.forEach { append("- ${it.text}\n") }
            append("\n【上一篇日记】\n")
            append(store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.journal ?: "（暂无）")
            append("\n")
            append("\n【上次整理以来的聊天】")
            append(transcript.take(400_000))
        }
        val client = DeepSeekClient(store::addUsage)
        val raw = client.complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", INSTRUCTIONS) },
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
        store.lastDreamAt = startedAt
        return entry
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
