package com.jack.ai.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jack.ai.MainActivity
import com.jack.ai.R
import com.jack.ai.api.DeepSeekClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Kavi 主动找用户的一次判断：说了（sent）还是忍住了，说了什么、为什么；用户点了「有用」或「别为这种事找我」。 */
@Serializable
data class Nudge(
    val at: Long = System.currentTimeMillis(),
    val sent: Boolean,
    val text: String = "",
    /** 哪一类事，两三个字（「客户回访」「待办到期」），用户说「别为这种事找我」就按它屏蔽。 */
    val kind: String = "",
    val why: String = "",
    /** 没发出去时，是代码拦下的原因（静默时段、今天找过两次了……）；模型自己决定不说的为空。 */
    val blocked: String = "",
    /** useful / mute / 空。 */
    val feedback: String = "",
)

/**
 * Kavi 主动开口：每天两次（早上、傍晚）轻量回看，由它判断有没有值得打扰用户的事；
 * 「什么时候闭嘴」写在代码里：一天最多两次、深夜不找、同一件事不说两遍、用户屏蔽过的那类不说。
 * 说的话进「💡 Kavi 找你」对话，通知点开就能接着聊。忍住没说的也记着，用户能看到。
 */
object Proactive {
    const val MAX_PER_DAY = 2
    /** 23 点到早上 8 点不找。 */
    const val QUIET_FROM = 23
    const val QUIET_UNTIL = 8
    const val CONV_ID = "kavi-nudge"
    const val TITLE = "💡 Kavi 找你"
    private const val CHANNEL = "nudge"
    /** 早上、傍晚各看一次。 */
    val SLOTS = listOf("morning" to (8 to 40), "evening" to (18 to 10))

    // ---------- 规矩（纯逻辑，能测） ----------

    fun quiet(hour: Int) = hour >= QUIET_FROM || hour < QUIET_UNTIL

    /** 现在能不能开口；不能就返回原因。 */
    fun gate(log: List<Nudge>, now: Long, hour: Int): String? {
        if (quiet(hour)) return "深夜不打扰（$QUIET_FROM 点到 $QUIET_UNTIL 点）"
        val day = dayOf(now)
        if (log.count { it.sent && dayOf(it.at) == day } >= MAX_PER_DAY) return "今天已经找过 $MAX_PER_DAY 次了"
        return null
    }

    /** 模型想说的这条要不要拦下：用户屏蔽了这一类、或者三天内说过差不多的。 */
    fun veto(log: List<Nudge>, kind: String, text: String, now: Long): String? {
        if (kind.isNotBlank() && kind in muted(log)) return "用户说过别为「$kind」这种事找他"
        val recent = log.filter { it.sent && now - it.at < 3 * 86_400_000L }
        if (recent.any { it.text == text || (kind.isNotBlank() && it.kind == kind && similar(it.text, text)) }) return "三天内说过差不多的"
        return null
    }

    fun muted(log: List<Nudge>): Set<String> = log.filter { it.feedback == "mute" && it.kind.isNotBlank() }.map { it.kind }.toSet()

    /** 粗略看两句话像不像：共有的字占短的那句一大半。 */
    fun similar(a: String, b: String): Boolean {
        val x = a.filter { !it.isWhitespace() }.toSet()
        val y = b.filter { !it.isWhitespace() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        return (x intersect y).size >= minOf(x.size, y.size) * 0.8
    }

    private fun dayOf(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    // ---------- 调度 ----------

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        SLOTS.forEach { (name, hm) ->
            if (!enabled) { wm.cancelUniqueWork("nudge-$name"); return@forEach }
            val req = PeriodicWorkRequestBuilder<NudgeWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayTo(hm.first, hm.second), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(workDataOf("slot" to name))
                .build()
            wm.enqueueUniquePeriodicWork("nudge-$name", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private fun delayTo(h: Int, m: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    // ---------- 回看 ----------

    /** 交给 Kavi 判断的材料：最近的对话、日记里还在进行的事、到期该回访的客户、今明两天的日程、最近的后台任务、它以前找过用户的记录。 */
    fun material(ctx: Context, store: Store, now: Long): String = buildString {
        val f = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val projects = store.loadProjects()
        val recap = Recap.build(Conversation(id = "-"), store.loadConversations().filter { it.id != CONV_ID }, projects, now)
        append("【最近的对话】\n").append(recap.ifBlank { "（最近几天没聊过）" }).append("\n\n")
        store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.let { append("【你上一篇日记】\n").append(it.journal.take(3000)).append("\n\n") }
        // 表里写着「下次联系」的：今天和以前的都算到期
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val due = runCatching {
            Tables.list(ctx).flatMap { t ->
                val i = t.columns.indexOf("下次联系").takeIf { it >= 0 } ?: return@flatMap emptyList()
                t.rows.filter { r -> r.getOrNull(i)?.trim()?.take(10)?.let { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) && it <= today } == true }
                    .map { r -> "「${t.name}」" + t.columns.zip(r).filter { it.second.isNotBlank() }.take(6).joinToString("，") { "${it.first}：${it.second.take(40)}" } }
            }
        }.getOrDefault(emptyList())
        append("【到了该联系的日子的】\n").append(due.take(15).joinToString("\n").ifBlank { "（没有）" }).append("\n\n")
        val events = runCatching { if (CalendarTool.canRead(ctx)) CalendarTool.list(ctx, now, now + 36 * 3_600_000L) else null }.getOrNull()
        if (events != null) append("【接下来 36 小时的日程】\n").append(events.take(10).joinToString("\n") { "${f.format(Date(it.begin))} ${it.title}" }.ifBlank { "（没有）" }).append("\n\n")
        val runs = runCatching { RunLog.all(ctx).filter { now - it.startedAt < 86_400_000L } }.getOrDefault(emptyList())
        append("【最近 24 小时的后台任务】（已经按自己的规则通知过用户的，别重复）\n")
            .append(runs.takeLast(10).joinToString("\n") { "${f.format(Date(it.startedAt))} ${it.title}：${it.status} ${(it.note.ifBlank { it.error }).take(100)}" }.ifBlank { "（没有）" }).append("\n\n")
        val log = store.loadNudges()
        append("【你以前主动找用户的记录】\n")
            .append(log.filter { it.sent }.takeLast(8).joinToString("\n") { "${f.format(Date(it.at))}［${it.kind}］${it.text}" + when (it.feedback) { "useful" -> "（用户点了有用）"; "mute" -> "（用户说别为这种事找他）"; else -> "" } }.ifBlank { "（还没有）" })
        muted(log).takeIf { it.isNotEmpty() }?.let { append("\n用户不想被打扰的事：" + it.joinToString("、")) }
    }

    private const val RULES = """现在是一次「要不要主动找用户」的回看。用户此刻没在和你聊天；你说的话会推送到他手机上，会打断他。
默认不说。只有同时满足这三条才说：
1. 有时效：现在不说，过了就晚了或损失明显（今天该回访的客户、快到期的事、明天一早的安排要提前准备）；
2. 用户能做点什么：说完他知道下一步干嘛；
3. 他多半还不知道：已设好的提醒会自己响、后台任务已经通知过的、他刚聊过的，都不用再说。
闲聊、关心、「好久不见」、复述日记、泛泛的建议，都不说。拿不准就不说。
要说的话：一件事，一两句（不超过 80 字），像朋友发的一条消息，直接说事和建议的下一步，不寒暄、不加表情。
只输出一个 JSON：{"send": true 或 false, "kind": "两三个字的类别（客户回访、待办到期、日程准备……）", "text": "要发的话（不发就空）", "why": "一句话说为什么发或为什么不发"}"""

    /** 回看一次：判断、按规矩拦、发通知、记一笔。返回这次的记录（没配好模型返回 null）。 */
    suspend fun check(ctx: Context, slot: String = "", force: Boolean = false): Nudge? {
        val store = Store(ctx)
        val s = store.loadSettings()
        if (!s.proactive && !force) return null
        val ep = s.backgroundEndpoint() ?: return null
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val log = store.loadNudges()
        gate(log, now, hour)?.let { if (!force) return record(store, Nudge(now, sent = false, blocked = it)) }
        KaviSelf.ensure(ctx)
        val time = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date(now))
        val raw = DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", KaviSelf.text(editable = false) + "\n\n" + RULES) },
                buildJsonObject { put("role", "user"); put("content", "现在是$time（${if (slot == "evening") "傍晚" else "早上"}的回看）。\n\n" + material(ctx, store, now)) },
            ),
            maxTokens = 1024,
            jsonMode = true,
        )
        val o = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull()
            ?: return record(store, Nudge(now, sent = false, blocked = "回看结果格式不对"))
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty().trim()
        val text = str("text").take(160)
        val kind = str("kind").take(10)
        val want = str("send") == "true" && text.isNotBlank()
        if (!want) return record(store, Nudge(now, sent = false, kind = kind, why = str("why")))
        veto(log, kind, text, now)?.let { return record(store, Nudge(now, sent = false, text = text, kind = kind, why = str("why"), blocked = it)) }
        val n = record(store, Nudge(now, sent = true, text = text, kind = kind, why = str("why")))
        // 进「Kavi 找你」对话（App 打开时并进来），点通知直接打开它接着聊
        store.addInbox(InboxItem("nudge", CONV_ID, TITLE, "", text, at = now, aside = true))
        notify(ctx, n)
        return n
    }

    private fun record(store: Store, n: Nudge): Nudge { store.addNudge(n); return n }

    private fun notify(ctx: Context, n: Nudge) {
        if (!Routines.canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kavi 主动找你", NotificationManager.IMPORTANCE_DEFAULT))
        val id = n.at.toInt()
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CHAT)
            .putExtra("convId", CONV_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun fb(v: String, code: Int) = PendingIntent.getBroadcast(ctx, id * 4 + code, Intent(ctx, NudgeFeedback::class.java)
            .putExtra("at", n.at).putExtra("v", v).putExtra("nid", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle("Kavi")
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "有用", fb("useful", 1))
            .addAction(0, "别为这种事找我", fb("mute", 2))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, notification) } catch (_: SecurityException) {}
    }

    fun feedback(ctx: Context, at: Long, value: String) {
        val store = Store(ctx)
        store.saveNudges(store.loadNudges().map { if (it.at == at) it.copy(feedback = value) else it })
    }
}

class NudgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Proactive.check(applicationContext, inputData.getString("slot").orEmpty())
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 1) Result.retry() else Result.failure()
    }
}

/** 通知上的「有用」「别为这种事找我」。 */
class NudgeFeedback : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val at = intent.getLongExtra("at", 0)
        val v = intent.getStringExtra("v") ?: return
        val pending = goAsync()
        Thread {
            try {
                Proactive.feedback(ctx.applicationContext, at, v)
                NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("nid", 0))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
