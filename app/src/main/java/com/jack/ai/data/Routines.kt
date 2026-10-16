package com.jack.ai.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jack.ai.MainActivity
import com.jack.ai.R
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.Agent
import com.jack.ai.api.StreamEvent
import com.jack.ai.api.TavilyClient
import com.jack.ai.api.formatResultsForModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 提醒和定时任务。
 * kind = "reminder"：到点弹一条通知；kind = "task"：到点让 AI 在后台干活，结果发到专门的对话里。
 * repeat = none / daily / weekdays / weekly。date 是第一次（或唯一一次）的日期 yyyy-MM-dd。
 */
@Serializable
data class Routine(
    val id: String = newId(),
    val kind: String = "reminder",
    val title: String,
    val prompt: String = "",
    val hour: Int,
    val minute: Int,
    val repeat: String = "none",
    val date: String = "",
    val enabled: Boolean = true,
    val convId: String = newId(),
    val lastRunAt: Long = 0,
    /** 定时任务碰上 DeepSeek 高峰时段时，顺延到空闲时段再跑（省一半钱）。 */
    val offPeak: Boolean = false,
)

/** 后台任务的结果，等 App 打开时并入对应的对话。 */
@Serializable
data class InboxItem(
    val routineId: String,
    val convId: String,
    val title: String,
    val prompt: String,
    val result: String,
    val at: Long = System.currentTimeMillis(),
)

val REPEAT_LABELS = linkedMapOf("none" to "只一次", "daily" to "每天", "weekdays" to "工作日", "weekly" to "每周")

private val DAY_FMT get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

fun Routine.describe(): String {
    val hm = String.format(Locale.US, "%02d:%02d", hour, minute)
    return when (repeat) {
        "daily" -> "每天 $hm"
        "weekdays" -> "工作日 $hm"
        "weekly" -> {
            val c = Calendar.getInstance().apply { runCatching { DAY_FMT.parse(date) }.getOrNull()?.let { time = it } }
            "每周" + "日一二三四五六"[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + hm
        }
        else -> runCatching { SimpleDateFormat("M月d日", Locale.CHINA).format(DAY_FMT.parse(date)!!) }.getOrDefault(date) + " " + hm
    }
}

/** 下一次触发的时间，没有了返回 null。 */
fun Routine.nextAt(now: Long = System.currentTimeMillis()): Long? {
    if (!enabled) return null
    val anchor = runCatching { DAY_FMT.parse(date) }.getOrNull()
    fun at(c: Calendar) = c.apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (repeat == "none") {
        val c = Calendar.getInstance().apply { if (anchor != null) time = anchor }
        return at(c).timeInMillis.takeIf { it > now }
    }
    val c = at(Calendar.getInstance().apply { timeInMillis = now })
    val anchorDow = Calendar.getInstance().apply { if (anchor != null) time = anchor }.get(Calendar.DAY_OF_WEEK)
    repeat(8) {
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val ok = when (repeat) {
            "weekdays" -> dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
            "weekly" -> dow == anchorDow
            else -> true
        }
        if (ok && c.timeInMillis > now && (anchor == null || c.timeInMillis >= anchor.time)) return c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
    }
    return null
}

object Routines {
    private const val CHANNEL = "routines"

    fun scheduleAll(ctx: Context) = Store(ctx).loadRoutines().forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, r: Routine) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, r.id)
        am.cancel(pi)
        val t = r.nextAt() ?: return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, id))
        WorkManager.getInstance(ctx).cancelAllWorkByTag("routine:$id")
    }

    private fun alarmIntent(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction("com.jack.ai.ROUTINE").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(ctx: Context, id: String, title: String, text: String, openChat: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "提醒和定时任务", NotificationManager.IMPORTANCE_HIGH))
        }
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CHAT)
                .putExtra("convId", openChat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    /** 到点了：提醒直接弹通知，任务交给后台去跑。然后排下一次。 */
    fun fire(ctx: Context, id: String) {
        val store = Store(ctx)
        val list = store.loadRoutines()
        val r = list.firstOrNull { it.id == id } ?: return
        if (!r.enabled) return
        if (r.kind == "reminder") {
            notify(ctx, r.id, "⏰ 提醒", r.title)
        } else {
            val now = System.currentTimeMillis()
            val wait = if (r.offPeak) Pricing.nextOffPeak(now) - now else 0L
            val req = OneTimeWorkRequestBuilder<RoutineWorker>()
                .addTag("routine:${r.id}")
                .setInputData(workDataOf("id" to r.id))
                .setInitialDelay(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
        val updated = r.copy(lastRunAt = System.currentTimeMillis(), enabled = r.repeat != "none")
        store.saveRoutines(list.map { if (it.id == id) updated else it })
        com.jack.ai.AskWidget.refresh(ctx)
        schedule(ctx, updated)
    }

    /** 后台跑一次任务：AI 可以联网搜索，最多 6 轮工具。 */
    suspend fun runTask(ctx: Context, r: Routine): String {
        val store = Store(ctx)
        val s = store.loadSettings()
        val ep = s.backgroundEndpoint() ?: return "还没有填写 API Key，任务没法执行。"
        val client = DeepSeekClient()
        val tavily = TavilyClient()
        val webClient = com.jack.ai.api.WebClient(tavily) { store.addPaidCall(com.jack.ai.api.WebClient.USAGE_KEY, com.jack.ai.api.WebClient.PRICE) }
        val web = s.tavilyKey.isNotBlank() || s.apiKey.isNotBlank()
        val tools = if (web) JsonArray(listOf(SkillTools.WEB_SEARCH, SkillTools.READ_WEBPAGE)) else null
        val today = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date())
        val memories = if (s.memoryEnabled) store.loadMemories() else emptyList()
        val system = buildString {
            if (s.developerPrompt.isNotBlank()) append(s.developerPrompt.trim()).append("\n\n")
            append("现在是$today。\n")
            if (s.userName.isNotBlank()) append("用户希望被称呼为：${s.userName}\n")
            if (s.aboutMe.isNotBlank()) append("关于用户：${s.aboutMe}\n")
            if (memories.isNotEmpty()) append("关于用户的长期记忆：\n" + memories.joinToString("\n") { "- ${it.text}" } + "\n")
            append("\n你正在后台执行用户预先设定的定时任务「${r.title}」。用户此刻不在线，不能向他提问，直接把任务做完。")
            if (web) append("需要最新信息时用 web_search 搜索，用 [编号](链接) 标出处。")
            append("输出就是用户稍后会看到的消息：直接给内容，简洁、好读，不要说“好的”“根据你的任务”之类的开场。")
        }
        val messages = mutableListOf(
            buildJsonObject { put("role", "system"); put("content", system) },
            buildJsonObject { put("role", "user"); put("content", r.prompt.ifBlank { r.title }) },
        )
        var sources = 0
        val agent = Agent({ msgs, t -> client.stream(ep, msgs, ThinkLevel.OFF, 1.0f, t) })
        agent.run(
            messages = messages,
            tools = tools,
            maxRounds = { 6 },
            execute = { name, args ->
                val obj = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull()
                fun arg(k: String) = (obj?.get(k) as? JsonPrimitive)?.content.orEmpty()
                try {
                    when (name) {
                        "web_search" -> webClient.search(s, arg("query")).let {
                            formatResultsForModel(it, sources + 1).also { _ -> sources += it.size }
                        }
                        "read_webpage" -> webClient.read(s, arg("url"))
                        else -> "这个工具不可用"
                    }
                } catch (e: Exception) {
                    "工具调用失败：${e.message}"
                }
            },
            onUsage = { ev -> store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens) },
        )
        return agent.lastRound.trim().ifBlank { "（这次没有生成内容）" }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.MY_PACKAGE_REPLACED" -> Routines.scheduleAll(ctx)
            else -> intent.getStringExtra("id")?.let { Routines.fire(ctx, it) }
        }
    }
}

class RoutineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val store = Store(applicationContext)
        val r = store.loadRoutines().firstOrNull { it.id == id } ?: return Result.failure()
        // 单次任务在 fire() 后会自动标记为完成，仍要允许这次执行。
        if (!r.enabled && r.repeat != "none") return Result.success()
        return try {
            val result = Routines.runTask(applicationContext, r)
            store.addInbox(InboxItem(r.id, r.convId, r.title, r.prompt, result))
            Routines.notify(applicationContext, r.id, r.title, result.take(300), openChat = r.convId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else {
                store.addInbox(InboxItem(r.id, r.convId, r.title, r.prompt, "这次任务没完成：${e.message ?: "网络问题"}"))
                Result.failure()
            }
        }
    }
}
