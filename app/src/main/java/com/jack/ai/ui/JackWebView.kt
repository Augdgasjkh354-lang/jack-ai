package com.jack.ai.ui

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.os.Build
import kotlin.math.abs
import android.view.View
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kavi 浏览器的网页窗口：长按选中文字时，系统弹出的「复制、全选……」那排里多一个「问 Kavi」；
 * 往下翻收起浏览器的顶栏底栏、往回翻放出来；从屏幕左右边缘往里滑后退、前进。
 * 这些只在用户看着的那个标签页上起作用。
 */
class JackWebView(ctx: Context) : WebView(ctx) {
    private val dp = resources.displayMetrics.density
    private fun mine() = JackBrowser.userViewing && JackBrowser.tab.web === this

    // ---- 翻页时收起、放出顶栏底栏 ----
    /** 同一个方向累计翻了多少（像素）：往下为正。 */
    private var run = 0

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!mine()) return
        val dy = t - oldt
        run = if ((dy > 0) == (run > 0)) run + dy else dy
        when {
            t <= 0 -> JackBrowser.barsHidden = false
            run > 48 * dp -> JackBrowser.barsHidden = true
            run < -24 * dp -> JackBrowser.barsHidden = false
        }
    }

    // ---- 从边缘滑：左边后退、右边前进 ----
    /** 按下时在哪条边上（-1 左、1 右、0 不在边上）。 */
    private var edge = 0
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val zone = EDGE_DP * dp
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swiping = false
                downX = e.x; downY = e.y
                edge = if (!mine()) 0 else if (e.x < zone) -1 else if (e.x > width - zone) 1 else 0
            }
            MotionEvent.ACTION_MOVE -> if (edge != 0) {
                val inward = if (edge < 0) e.x - downX else downX - e.x
                if (!swiping && inward > 16 * dp && inward > 2 * abs(e.y - downY)) {
                    // 认定是边缘滑动：网页那边当这次按下取消了
                    swiping = true
                    val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(c)
                    c.recycle()
                }
                if (swiping) { JackBrowser.edgeSwipe = edge to (inward / (PULL_DP * dp)).coerceIn(0f, 1f); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                val done = e.actionMasked == MotionEvent.ACTION_UP && (JackBrowser.edgeSwipe?.second ?: 0f) >= 1f
                val side = edge
                JackBrowser.edgeSwipe = null
                swiping = false; edge = 0
                if (done) JackBrowser.edgeGo(side)
                return true
            }
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 全面屏手势的手机：两边边缘本来是系统的「返回」。右边中间留一段给「前进」（系统最多让出 200dp 高），左边照旧交给系统返回（也是后退）
        if (changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val w = r - l; val h = b - t; val half = (100 * dp).toInt()
            systemGestureExclusionRects = listOf(Rect(w - (EDGE_DP * dp).toInt(), h / 2 - half, w, h / 2 + half))
        }
    }

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
        /** 边缘多宽内按下算边缘滑动。 */
        const val EDGE_DP = 20
        /** 往里拉多远松手才跳。 */
        const val PULL_DP = 90
    }
}
