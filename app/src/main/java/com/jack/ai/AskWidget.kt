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
import com.jack.ai.data.nextAt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 桌面小组件：点输入条开新对话，点话筒直接语音提问；下面一行显示下一个提醒、日程和待复习的卡片。 */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)

    companion object {
        /** 提醒、卡片、日程、对话、表格有变化时调用，刷新桌面上所有的小组件。 */
        fun refresh(ctx: Context) = Widgets.refreshAll(ctx)

        internal fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_ask)
            views.setOnClickPendingIntent(R.id.widget_root, open(ctx, MainActivity.ACTION_NEW_CHAT, 1))
            views.setOnClickPendingIntent(R.id.widget_mic, open(ctx, MainActivity.ACTION_VOICE, 2))
            val sub = runCatching { summary(ctx) }.getOrDefault("")
            views.setTextViewText(R.id.widget_sub, sub)
            views.setViewVisibility(R.id.widget_sub, if (sub.isEmpty()) View.GONE else View.VISIBLE)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun summary(ctx: Context): String {
            val store = Store(ctx)
            val now = System.currentTimeMillis()
            val parts = mutableListOf<String>()
            store.loadRoutines().mapNotNull { r -> r.nextAt(now)?.let { it to r } }.minByOrNull { it.first }?.let { (t, r) ->
                parts += "⏰ ${whenText(t, now)} ${r.title.take(14)}"
            }
            // 24 小时内的下一个日程（有日历权限时）
            if (CalendarTool.canRead(ctx)) {
                CalendarTool.list(ctx, now, now + 86_400_000L).firstOrNull { !it.allDay && it.begin >= now }?.let { e ->
                    parts += "📅 ${whenText(e.begin, now)} ${e.title.take(14)}"
                }
            }
            val due = store.loadCards().count { it.due <= now }
            if (due > 0) parts += "📚 $due 张卡片待复习"
            return parts.joinToString("  ")
        }

        private fun whenText(t: Long, now: Long): String {
            val day = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
            val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
            return when (day(t) - day(now)) {
                0 -> "今天 $hm"
                1 -> "明天 $hm"
                else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t))
            }
        }

        private fun open(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, MainActivity::class.java).setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
