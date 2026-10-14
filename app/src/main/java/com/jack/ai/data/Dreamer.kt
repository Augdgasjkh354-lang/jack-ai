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
 * 补充进「记忆」，并把全部记忆精简合并，最后写一篇“昨夜的梦”。
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

    private const val INSTRUCTIONS = """你是一个私人 AI 助手，现在是你“睡觉做梦”的时间：你要整理用户最近的聊天，更新你对用户的长期记忆。

你会收到：
1. 当前的全部记忆（每条一行，可能为空）
2. 上次整理以来的所有聊天记录

请完成三件事：
一、写“summary”：用几条简洁的条目，概括这段时间都聊了什么。
二、更新“memories”：输出整理后的【完整】记忆列表。
   - 从聊天中找出值得长期记住的信息：用户的身份背景、长期偏好和习惯、重要的人和事、正在进行的计划、做过的决定、明确说“记住”的内容。
   - 一次性的问题、临时的闲聊、常识性内容不要记。
   - 把已有记忆和新信息合并：重复的合成一条，过时或被推翻的更新或删掉，表述精简但保留关键细节（名字、数字、日期）。
   - 每条记忆是一句完整、独立能看懂的话，用“用户……”开头描述用户，总数尽量不超过 80 条。
三、写“dream”：以第一人称写一段 150 到 300 字的梦，把这段时间聊过的内容化成梦里的意象和场景，有一点梦的朦胧和诗意，但能让用户认出是哪些事。温柔、真诚，不要说教。

然后写“changes”：一两句话说明这次新记住了什么、改了什么、忘掉了什么。

最后写“note”：醒来后想对用户说的一句话（30 到 80 字）。像一个了解他的老朋友，结合这段时间他在忙的、在意的、烦恼的事，真诚地说一句关心、鼓励或提醒。不要客套，不要说教，不要用“亲爱的”。

只输出一个 JSON 对象，格式：
{"summary": "…", "memories": ["…", "…"], "dream": "…", "changes": "…", "note": "…"}"""

    /**
     * 真正做梦的逻辑。没有新聊天、没填 Key 时返回 null。
     */
    suspend fun dream(ctx: Context, force: Boolean = false): DreamEntry? {
        val store = Store(ctx)
        val settings = store.loadSettings()
        if (settings.apiKey.isBlank() || (!force && !settings.dreamEnabled)) return null
        val since = store.lastDreamAt.takeIf { it > 0 } ?: (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        val transcript = StringBuilder()
        store.loadConversations()
            .filter { c -> c.messages.any { it.createdAt > since } }
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
            store.lastDreamAt = System.currentTimeMillis()
            return null
        }
        val memories = store.loadMemories()
        val input = buildString {
            append("【当前记忆】\n")
            if (memories.isEmpty()) append("（暂无）\n") else memories.forEach { append("- ${it.text}\n") }
            append("\n【上次整理以来的聊天】")
            append(transcript.take(400_000))
        }
        val client = DeepSeekClient(store::addUsage)
        val raw = client.complete(
            settings.baseUrl, settings.apiKey, MODEL_FLASH,
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
        store.saveMemories(newMemories.map { old[it] ?: MemoryItem(text = it) })
        val entry = DreamEntry(dream = str("dream"), summary = str("summary"), changes = str("changes"), note = str("note"))
        store.saveDreams(listOf(entry) + store.loadDreams().take(59))
        store.lastDreamAt = System.currentTimeMillis()
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
