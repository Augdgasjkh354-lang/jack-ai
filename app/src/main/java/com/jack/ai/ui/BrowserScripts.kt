package com.jack.ai.ui

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive

/** 包一层 assets/jack_browser.js：每次调用前注入（页面跳转后就没了，注入过的不会重复定义）。 */
object BrowserScripts {
    @Volatile private var lib: String? = null

    private fun lib(ctx: Context): String =
        lib ?: ctx.assets.open("jack_browser.js").bufferedReader().use { it.readText() }.also { lib = it }

    private fun q(s: String) = JsonPrimitive(s).toString()

    fun snapshot(ctx: Context) = "${lib(ctx)}\nwindow.__jack.snapshot()"
    fun inspect(ctx: Context, n: Int) = "${lib(ctx)}\nwindow.__jack.inspect($n)"
    fun act(ctx: Context, n: Int, op: String, arg: String, clear: Boolean) =
        "${lib(ctx)}\nwindow.__jack.act($n, ${q(op)}, ${q(arg)}, $clear)"
    fun markText(ctx: Context, text: String) = "${lib(ctx)}\nwindow.__jack.markText(${q(text)})"
    fun at(ctx: Context, x: Float, y: Float) = "${lib(ctx)}\nwindow.__jack.at($x, $y)"
    fun center(ctx: Context, n: Int) = "${lib(ctx)}\nwindow.__jack.center($n)"
    fun signature(ctx: Context) = "${lib(ctx)}\nwindow.__jack.signature()"
    fun hasText(ctx: Context, text: String) = "${lib(ctx)}\nwindow.__jack.hasText(${q(text)})"
    fun extract(ctx: Context, what: String, start: Int, limit: Int) = "${lib(ctx)}\nwindow.__jack.extract(${q(what)}, $start, $limit)"
}
