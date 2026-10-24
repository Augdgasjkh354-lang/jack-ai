package com.jack.ai.ui

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Jack 浏览器的网页窗口：长按选中文字时，系统弹出的「复制、全选……」那排里多一个「问 Kavi」。 */
class JackWebView(ctx: Context) : WebView(ctx) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(Ask(callback), type)
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(Ask(callback))

    private inner class Ask(val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val ok = inner.onCreateActionMode(mode, menu)
            if (JackBrowser.askSelection != null) menu.add(Menu.NONE, ASK, 0, "问 Kavi")
            return ok
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ASK) return inner.onActionItemClicked(mode, item)
            evaluateJavascript("window.getSelection().toString()") { r ->
                val text = runCatching { (Json.parseToJsonElement(r) as? JsonPrimitive)?.content }.getOrNull().orEmpty()
                if (text.isNotBlank()) JackBrowser.askSelection?.invoke(text)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        const val ASK = 0x4a41
    }
}
