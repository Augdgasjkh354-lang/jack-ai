package com.jack.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.jack.ai.data.CalendarTool
import com.jack.ai.data.Store
import com.jack.ai.data.Table
import com.jack.ai.data.Tables
import com.jack.ai.data.costYuan
import com.jack.ai.data.nextAt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 所有桌面小组件的刷新入口：数据变了调一下，没放到桌面上的不会去算。 */
object Widgets {
    fun refreshAll(ctx: Context) {
        // 要读文件，放到后台线程
        val app = ctx.applicationContext
        Thread { renderAll(app) }.start()
    }

    private fun renderAll(ctx: Context) {
        runCatching {
            val m = AppWidgetManager.getInstance(ctx)
            fun ids(c: Class<*>) = m.getAppWidgetIds(ComponentName(ctx, c))
            ids(AskWidget::class.java).takeIf { it.isNotEmpty() }?.let { AskWidget.render(ctx, m, it) }
            ids(QuickWidget::class.java).takeIf { it.isNotEmpty() }?.let { QuickWidget.render(ctx, m, it) }
            ids(TodayWidget::class.java).takeIf { it.isNotEmpty() }?.let { TodayWidget.render(ctx, m, it) }
            ids(RecentWidget::class.java).takeIf { it.isNotEmpty() }?.let { RecentWidget.render(ctx, m, it) }
            ids(LedgerWidget::class.java).takeIf { it.isNotEmpty() }?.let { LedgerWidget.render(ctx, m, it) }
        }
    }

    /** 打开 App 做某件事。code 要各不相同，不然 PendingIntent 会互相覆盖。 */
    fun open(ctx: Context, action: String, code: Int, extras: (Intent) -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).also(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun openApp(ctx: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun whenText(t: Long, now: Long): String {
        val day = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
        return when (day(t) - day(now)) {
            0 -> "今天 $hm"
            1 -> "明天 $hm"
            -1 -> "昨天 $hm"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
        }
    }

    fun yuan(v: Double) = "¥" + if (v >= 100 || v == Math.floor(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)

    /** 列表式小组件（标题 + 最多 4 行）。lines 里第二项是点这一行要做的事，可以为空。 */
    fun renderList(ctx: Context, title: String, lines: List<Pair<String, PendingIntent?>>, empty: String, root: PendingIntent): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_list)
        v.setTextViewText(R.id.l_title, title)
        v.setOnClickPendingIntent(R.id.l_root, root)
        listOf(R.id.l_1, R.id.l_2, R.id.l_3, R.id.l_4).forEachIndexed { i, id ->
            val line = lines.getOrNull(i)
            if (line == null) v.setViewVisibility(id, View.GONE) else {
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, line.first)
                v.setOnClickPendingIntent(id, line.second ?: root)
            }
        }
        v.setTextViewText(R.id.l_empty, empty)
        v.setViewVisibility(R.id.l_empty, if (lines.isEmpty()) View.VISIBLE else View.GONE)
        return v
    }
}

/** 快捷按钮（2×2）：Chat、Work、语音、拍照。 */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)
    companion object {
        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
            v.setOnClickPendingIntent(R.id.q_chat, Widgets.open(ctx, MainActivity.ACTION_NEW_CHAT, 11))
            v.setOnClickPendingIntent(R.id.q_work, Widgets.open(ctx, MainActivity.ACTION_NEW_WORK, 12))
            v.setOnClickPendingIntent(R.id.q_voice, Widgets.open(ctx, MainActivity.ACTION_VOICE, 13))
            v.setOnClickPendingIntent(R.id.q_camera, Widgets.open(ctx, MainActivity.ACTION_CAMERA, 14))
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}

/** 今日概览（4×2）：下一个提醒、今天的日程、待复习、本月花费。 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)
    companion object {
        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val store = Store(ctx)
            val lines = mutableListOf<Pair<String, PendingIntent?>>()
            runCatching {
                store.loadRoutines().mapNotNull { r -> r.nextAt(now)?.let { it to r } }.minByOrNull { it.first }?.let { (t, r) ->
                    lines += "⏰ ${Widgets.whenText(t, now)}  ${r.title}" to null
                }
            }
            runCatching {
                if (CalendarTool.canRead(ctx)) {
                    val end = Calendar.getInstance().apply {
                        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    }.timeInMillis
                    CalendarTool.list(ctx, now, end).filter { it.allDay || it.begin >= now }.take(2).forEach { e ->
                        lines += "📅 " + (if (e.allDay) "今天" else SimpleDateFormat("HH:mm", Locale.US).format(Date(e.begin))) + "  ${e.title}" to null
                    }
                }
            }
            runCatching {
                val due = store.loadCards().count { it.due <= now }
                if (due > 0) lines += "📚 $due 张卡片待复习" to null
            }
            runCatching {
                val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(now))
                val spent = store.loadUsage().filter { it.date.startsWith(month) }.sumOf { it.costYuan() ?: 0.0 }
                val budget = store.loadSettings().monthlyBudget
                lines += "💰 本月 AI 花了 ${Widgets.yuan(spent)}" + (if (budget > 0) " / 预算 ${Widgets.yuan(budget)}" else "") to null
            }
            val title = "今天 · " + SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now))
            val v = Widgets.renderList(ctx, title, lines.take(4), "今天没有安排", Widgets.openApp(ctx, 21))
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}

/** 最近对话（4×2）：点一行直接进那个对话。 */
class RecentWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)
    companion object {
        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val convs = runCatching { Store(ctx).loadConversations() }.getOrDefault(emptyList())
                .filter { it.messages.isNotEmpty() }.sortedByDescending { it.updatedAt }.take(4)
            val lines = convs.mapIndexed { i, c ->
                (if (c.work) "▸ " else "") + c.title + "  ·  " + Widgets.whenText(c.updatedAt, now) to
                    Widgets.open(ctx, MainActivity.ACTION_OPEN_CHAT, 31 + i) { it.putExtra("convId", c.id) }
            }
            val v = Widgets.renderList(ctx, "最近对话", lines, "还没有对话", Widgets.open(ctx, MainActivity.ACTION_NEW_CHAT, 30))
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}

/** 记账（3×1）：今天和本月花了多少，“记一笔”打开新对话并填好“记一下：”。 */
class LedgerWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)
    companion object {
        /** 找记账表：名字里有“记账”的优先，其次是有“日期”和“金额”两列的。 */
        fun ledger(ctx: Context): Table? {
            val all = Tables.list(ctx)
            return all.firstOrNull { "记账" in it.name }
                ?: all.firstOrNull { "日期" in it.columns && "金额" in it.columns }
        }

        fun totals(t: Table, now: Long): Pair<Double, Double> {
            val d = t.columns.indexOf("日期")
            val a = t.columns.indexOf("金额")
            if (d < 0 || a < 0) return 0.0 to 0.0
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
            fun amount(r: List<String>) = r[a].replace(",", "").replace("¥", "").replace("元", "").trim().toDoubleOrNull() ?: 0.0
            // 日期可能写成 2026-10-5、2026/10/05、2026年10月5日，统一成 2026-10-05 再比
            val month = t.rows.filter { Tables.normDate(it[d])?.startsWith(today.take(7)) == true }
            return month.filter { Tables.normDate(it[d]) == today }.sumOf(::amount) to month.sumOf(::amount)
        }

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_ledger)
            val t = runCatching { ledger(ctx) }.getOrNull()
            if (t == null) {
                v.setTextViewText(R.id.g_today, "还没有记账")
                v.setTextViewText(R.id.g_month, "点「记一笔」开始")
            } else {
                val (day, month) = totals(t, System.currentTimeMillis())
                v.setTextViewText(R.id.g_today, "今天 ${Widgets.yuan(day)}")
                v.setTextViewText(R.id.g_month, "本月 ${Widgets.yuan(month)} · ${t.name}")
            }
            v.setOnClickPendingIntent(R.id.g_add, Widgets.open(ctx, MainActivity.ACTION_NEW_CHAT, 41) { it.putExtra("prefill", "记一下：") })
            v.setOnClickPendingIntent(R.id.g_root, Widgets.openApp(ctx, 40))
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}
