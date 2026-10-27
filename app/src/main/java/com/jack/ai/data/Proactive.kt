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
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.ENGLISH)
        val projects = store.loadProjects()
        val recap = Recap.build(Conversation(id = "-"), store.loadConversations().filter { it.id != CONV_ID }, projects, now)
        append("【Recent conversations】\n").append(recap.ifBlank { "(no chats in the last few days)" }).append("\n\n")
        store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.let { append("【Your latest journal entry】\n").append(it.journal.take(3000)).append("\n\n") }
        // 表里写着「下次联系」的：今天和以前的都算到期
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val due = runCatching {
            Tables.list(ctx).flatMap { t ->
                val i = t.columns.indexOf("下次联系").takeIf { it >= 0 } ?: return@flatMap emptyList()
                t.rows.filter { r -> r.getOrNull(i)?.trim()?.take(10)?.let { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) && it <= today } == true }
                    .map { r -> "「${t.name}」" + t.columns.zip(r).filter { it.second.isNotBlank() }.take(6).joinToString("，") { "${it.first}：${it.second.take(40)}" } }
            }
        }.getOrDefault(emptyList())
        append("【Due for a follow-up】\n").append(due.take(15).joinToString("\n").ifBlank { "(none)" }).append("\n\n")
        val events = runCatching { if (CalendarTool.canRead(ctx)) CalendarTool.list(ctx, now, now + 36 * 3_600_000L) else null }.getOrNull()
        if (events != null) append("【Calendar for the next 36 hours】\n").append(events.take(10).joinToString("\n") { "${f.format(Date(it.begin))} ${it.title}" }.ifBlank { "(none)" }).append("\n\n")
        val runs = runCatching { RunLog.all(ctx).filter { now - it.startedAt < 86_400_000L } }.getOrDefault(emptyList())
        append("【Background tasks in the last 24 hours】(those already notified under their own rules shouldn't be repeated)\n")
            .append(runs.takeLast(10).joinToString("\n") { "${f.format(Date(it.startedAt))} ${it.title}：${it.status} ${(it.note.ifBlank { it.error }).take(100)}" }.ifBlank { "(none)" }).append("\n\n")
        val log = store.loadNudges()
        append("【Times you reached out before】\n")
            .append(log.filter { it.sent }.takeLast(8).joinToString("\n") { "${f.format(Date(it.at))}［${it.kind}］${it.text}" + when (it.feedback) { "useful" -> " (marked useful)"; "mute" -> " (asked not to be contacted about this kind of thing)"; else -> "" } }.ifBlank { "(none yet)" })
        muted(log).takeIf { it.isNotEmpty() }?.let { append("\nKinds of things not to reach out about: " + it.joinToString(", ")) }
    }

    private const val RULES = """This is a look-back to decide whether to reach out to {user} on your own. {User} isn't chatting with you right now; anything you say is pushed to their phone and will interrupt them.
Default to saying nothing. Speak only when all three hold:
1. It's time-sensitive: if you don't say it now, it'll be too late or cost something real (a client due for a follow-up today, something about to expire, an early plan tomorrow that needs preparing).
2. {User} can act on it: after reading, they know what to do next.
3. They probably don't know yet: reminders already set will ring by themselves, and background tasks that already notified them or things they just chatted about don't need repeating.
No small talk, check-ins, "long time no see", retelling the journal, or general advice. If unsure, say nothing.
If you do speak: one thing, one or two sentences (at most about 80 Chinese characters or 50 English words), like a message from a friend. State the matter and the suggested next step directly; no greetings, no emoji. Write it in {lang}, or in the language {user} uses in the recent conversations.
Output only one JSON object: {"send": true or false, "kind": "a two-or-three-word category (client follow-up, to-do due, schedule prep...)", "text": "the message to send (empty if not sending)", "why": "one sentence on why you're sending or not"}"""

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
        val time = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.ENGLISH).format(Date(now))
        val raw = DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(KaviSelf.text(editable = false) + Persona.nameLine(s).let { if (it.isEmpty()) "" else "\n\n$it" } + "\n\n" + CONFIDENTIAL_PROMPT + "\n\n" + RULES, s)) },
                buildJsonObject { put("role", "user"); put("content", "It is now $time (the ${if (slot == "evening") "evening" else "morning"} look-back).\n\n" + material(ctx, store, now)) },
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
