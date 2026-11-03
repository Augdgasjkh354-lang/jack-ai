package com.jack.ai.data

import android.content.Context
import java.io.File

/**
 * 操作其他 App 的实测成功率：Kavi 每做一步（打开、点、长按、输入、回车、翻页、划、返回）记一笔结果，按 App 分。
 * 和浏览器实测一样只记 App 名、动作和结果，不记屏幕上的字。看的是改了以后成功率有没有真的变好。
 * 被安全规则拦下的（付款页、没勾的 App）和用户没同意的不记：那不是没做成。
 */
object PhoneStats {
    /** 结果：ok 做成了、no_change 做了但屏幕没变、miss 清单里找不到要操作的那一项、stale 屏幕变了没先重读、failed 系统没让做成。 */
    val SUCCESS = setOf("ok")
    val LABELS = mapOf("ok" to "成功", "no_change" to "做了但屏幕没变", "miss" to "找不到要操作的那一项",
        "stale" to "屏幕变了没先重读", "failed" to "没做成")

    private fun file(ctx: Context) = File(ctx.filesDir, "phone_stats.json")

    fun load(ctx: Context): List<BrowserStats.Event> = BrowserStats.loadFrom(file(ctx))

    fun record(ctx: Context, app: String, action: String, outcome: String, now: Long = System.currentTimeMillis()) {
        if (app.isBlank()) return
        BrowserStats.appendTo(file(ctx), BrowserStats.Event(now, app, action, outcome))
    }

    /** 给自查和统计页看的几行（按 App）。没有记录返回空。 */
    fun summary(events: List<BrowserStats.Event>, now: Long = System.currentTimeMillis()): String =
        BrowserStats.summary(events, labels = LABELS, success = SUCCESS, now = now)
}
