package com.jack.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 桌面小组件：点输入条开新对话，点话筒直接语音提问。 */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_ask)
        views.setOnClickPendingIntent(R.id.widget_root, open(ctx, MainActivity.ACTION_NEW_CHAT, 1))
        views.setOnClickPendingIntent(R.id.widget_mic, open(ctx, MainActivity.ACTION_VOICE, 2))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
