package com.jack.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import com.jack.ai.api.BrowserPages

/**
 * App 里点链接都走这里：网页按设置用 Kavi 浏览器（默认）或手机的系统浏览器打开；
 * 电话、邮件这类别的链接照旧交给手机。
 */
object Links {
    /** 设置里「链接用 Kavi 浏览器打开」，直接读存储（点一下读一次，不用跟着设置刷新）。 */
    fun inApp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("linksInApp", true)

    fun open(ctx: Context, url: String) {
        if (BrowserPages.isWeb(url) && inApp(ctx)) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else system(ctx, url)
    }

    /** 不管设置，交给手机（系统浏览器或对应的 App）。 */
    fun system(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "手机上没有能打开它的 App", Toast.LENGTH_SHORT).show() }
    }
}

/** 放进 Compose 的 LocalUriHandler：回答里的链接、来源卡片、设置页的链接都经过它。 */
class JackUriHandler(private val ctx: Context) : UriHandler {
    override fun openUri(uri: String) = Links.open(ctx, uri)
}
