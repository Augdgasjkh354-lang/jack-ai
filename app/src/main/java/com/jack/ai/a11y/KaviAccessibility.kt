package com.jack.ai.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Kavi 的无障碍服务：读别的 App 的界面、点、输入、滑动、截图，替用户操作手机。
 * 只收用户勾过的 App 的事件（不留内容，只记时间，用来等界面停下来）；能不能碰哪个 App 由 PhoneGuard 和工具那边管。
 * 操作时屏幕上方有一条「Kavi 正在操作 · 停止」；要用户确认的事在屏幕下方弹一张卡片（Kavi 不在前台也看得到）。
 */
class KaviAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: KaviAccessibility? = null
            private set
        /** 最近一次界面变化的时间。 */
        @Volatile var lastEventAt = 0L
        /** 悬浮条上的「停止」按过几次：工具开始操作时记一下，变了就是用户叫停了。 */
        val stops = AtomicInteger()

        /** 系统设置里打开了没有（打开了但进程刚起来时 instance 可能还没连上）。 */
        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, KaviAccessibility::class.java).flattenToString()
            val list = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, true) }
        }

        /** 设置变了：只收勾过的 App 的事件。 */
        fun apply(s: Settings) { instance?.let { svc -> Handler(Looper.getMainLooper()).post { svc.filter(s.phoneApps) } } }

        /** 现在前台是别的 App（不是 Kavi 自己）：要确认的事得用悬浮卡片问。 */
        fun othersInFront(ctx: Context): Boolean = instance?.foreground()?.let { it != ctx.packageName } == true
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bar: View? = null
    private var barText: TextView? = null
    private var panel: View? = null
    private val hideBar = Runnable { removeBar() }
    private val asking = Mutex()

    override fun onServiceConnected() {
        instance = this
        filter(runCatching { Store(this).loadSettings().phoneApps }.getOrDefault(emptyList()))
    }

    private fun filter(apps: List<String>) {
        val info = serviceInfo ?: return
        // 一个都没勾：只收自己的（等于什么都不收）
        info.packageNames = apps.ifEmpty { listOf(packageName) }.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEventAt = System.currentTimeMillis() }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        clearOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearOverlays()
        instance = null
        super.onDestroy()
    }

    // ---------- 读 ----------

    /** 前台 App 的包名。 */
    fun foreground(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    fun appLabel(pkg: String): String = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** 把前台窗口的控件树拷出来（最多两千个控件）。 */
    fun snapshot(): Pair<String, UiNode>? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        var count = 0
        val r = Rect()
        fun copy(n: AccessibilityNodeInfo, depth: Int): UiNode? {
            if (count++ > 2000 || depth > 80) return null
            if (!n.isVisibleToUser) return null
            n.getBoundsInScreen(r)
            // 先记下自己的位置：下面拷孩子时 r 会被改掉
            val bounds = intArrayOf(r.left, r.top, r.right, r.bottom)
            val kids = (0 until n.childCount).map { i -> n.getChild(i)?.let { copy(it, depth + 1) } ?: placeholder() }
            return UiNode(
                cls = n.className?.toString().orEmpty(), text = n.text?.toString().orEmpty(), desc = n.contentDescription?.toString().orEmpty(),
                hint = n.hintText?.toString().orEmpty(), bounds = bounds,
                clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected, enabled = n.isEnabled, password = n.isPassword,
                children = kids,
            )
        }
        return pkg to (copy(root, 0) ?: UiNode())
    }

    /** 看不见的孩子也占一个位置，路径才对得上。 */
    private fun placeholder() = UiNode()

    /** 照路径在现在的控件树里找回那个控件；对不上返回 null。 */
    private fun find(path: List<Int>): AccessibilityNodeInfo? {
        var n = rootInActiveWindow ?: return null
        for (i in path) n = (if (i < n.childCount) n.getChild(i) else null) ?: return null
        return n
    }

    // ---------- 做 ----------

    /** 点一个控件：它自己或往上最近的能点的控件执行点击；不行就在它中间按一下。 */
    suspend fun click(e: Element, long: Boolean = false): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n = find(e.path)
        if (n != null && sameSpot(n, e)) {
            while (n != null) {
                if ((if (long) n.isLongClickable else n.isClickable) && n.performAction(action)) return true
                n = n.parent
            }
        }
        return tap(e.centerX, e.centerY, long)
    }

    private fun sameSpot(n: AccessibilityNodeInfo, e: Element): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        return r.contains(e.centerX, e.centerY)
    }

    /** 在屏幕上按一下（long：长按）。 */
    suspend fun tap(x: Int, y: Int, long: Boolean = false): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, if (long) 700 else 60)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 350): Boolean =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, ms)

    private suspend fun gesture(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, main)
        if (!ok && c.isActive) c.resume(false)
    }

    /** 在输入框里填字（替换原来的）：先直接设，不行就点一下再粘贴。 */
    suspend fun type(e: Element, text: String): Boolean {
        val n = find(e.path)?.takeIf { it.isEditable } ?: run { click(e); delay(300); rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kavi", text))
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** 按输入法上的「回车 / 发送 / 搜索」键（安卓 11 以上）。 */
    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** 滑动一个列表（down：看下面的内容）；没指定列表或列表滑不动，就在屏幕中间划一下。 */
    suspend fun scroll(e: Element?, direction: String): Boolean {
        if (e != null && direction in setOf("down", "up")) {
            val n = find(e.path)
            if (n != null && n.isScrollable && n.performAction(
                    if (direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        }
        val (w, h) = screenSize()
        val box = e?.bounds ?: intArrayOf(0, 0, w, h)
        val cx = (box[0] + box[2]) / 2; val cy = (box[1] + box[3]) / 2
        val dx = (box[2] - box[0]) * 3 / 10; val dy = (box[3] - box[1]) * 3 / 10
        return when (direction) {
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "left" -> swipe(cx + dx, cy, cx - dx, cy)
            "right" -> swipe(cx - dx, cy, cx + dx, cy)
            else -> swipe(cx, cy + dy, cx, cy - dy)
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 打开一个 App（从它的首页开始）。 */
    fun open(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(i) }.isSuccess
    }

    /** 等界面停下来：动完之后 0.4 秒没变化，最多等 maxMs。 */
    suspend fun settle(maxMs: Long = 3000) {
        delay(250)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs && System.currentTimeMillis() - lastEventAt < 400) delay(100)
    }

    fun screenSize(): Pair<Int, Int> {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /**
     * 截屏（安卓 11 以上），画上清单里能点、能输入的编号框，缩到最宽 720 像素的 JPEG。
     * 返回 data URL 和缩放比例（截图上的 1 像素 = 屏幕上多少像素）。
     */
    suspend fun screenshot(marks: List<Element>): Pair<String, Float>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val shot: Bitmap = suspendCancellableCoroutine { c ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val bmp = runCatching { Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    hb.close()
                    if (c.isActive) c.resume(bmp)
                }
                override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
            })
        } ?: return null
        val scale = minOf(1f, 720f / shot.width)
        val out = Bitmap.createBitmap((shot.width * scale).toInt(), (shot.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(scale, scale)
        c.drawBitmap(shot, 0f, 0f, null)
        shot.recycle()
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f / scale; color = Color.rgb(230, 40, 40) }
        val tag = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(230, 40, 40) }
        val txt = Paint().apply { color = Color.WHITE; textSize = 26f / scale; isAntiAlias = true; isFakeBoldText = true }
        marks.filter { it.clickable || it.editable }.forEach { e ->
            val (l, t, r, b) = e.bounds.map { it.toFloat() }
            c.drawRect(l, t, r, b, box)
            val label = e.n.toString()
            val tw = txt.measureText(label) + 8f / scale
            c.drawRect(l, t, l + tw, t + txt.textSize + 6f / scale, tag)
            c.drawText(label, l + 4f / scale, t + txt.textSize, txt)
        }
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
        out.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP) to 1f / scale
    }

    // ---------- 悬浮条和确认卡片 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 屏幕上方的小条：「Kavi 正在操作：……  停止」。一段时间没有新操作就自己收起来。 */
    fun showBar(text: String, keepMs: Long = 20_000) = main.post {
        if (bar == null) {
            val label = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 1; maxWidth = dp(240) }
            val stop = TextView(this).apply {
                this.text = "停止"; setTextColor(Color.rgb(255, 180, 171)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), 0, 0, 0)
                setOnClickListener {
                    stops.incrementAndGet()
                    barText?.text = "已停止，Kavi 不会再操作"
                    main.removeCallbacks(hideBar); main.postDelayed(hideBar, 2500)
                }
            }
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(230, 32, 33, 36)) }
                addView(label); addView(stop)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(40) }
            if (runCatching { wm.addView(v, lp) }.isSuccess) { bar = v; barText = label }
        }
        barText?.text = "Kavi：$text"
        main.removeCallbacks(hideBar)
        main.postDelayed(hideBar, keepMs)
    }

    private fun removeBar() { bar?.let { runCatching { wm.removeView(it) } }; bar = null; barText = null }

    private fun clearOverlays() = main.post { removeBar(); panel?.let { runCatching { wm.removeView(it) } }; panel = null }

    /** 在屏幕下方问用户一件事（三分钟没回就算不同意）。一次只问一件。 */
    suspend fun confirm(title: String, body: String, action: String): Boolean = asking.withLock {
        var answer: ((Boolean) -> Unit)? = null
        val got = withTimeoutOrNull(180_000) {
            suspendCancellableCoroutine { c ->
                answer = { ok -> if (c.isActive) c.resume(ok) }
                main.post { showPanel(title, body, action) { ok -> answer?.invoke(ok) } }
            }
        }
        main.post { panel?.let { runCatching { wm.removeView(it) } }; panel = null }
        got == true
    }

    private fun showPanel(title: String, body: String, action: String, onAnswer: (Boolean) -> Unit) {
        panel?.let { runCatching { wm.removeView(it) } }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.WHITE else Color.rgb(28, 28, 30)
        val bg = if (dark) Color.rgb(40, 42, 48) else Color.WHITE
        fun button(text: String, primary: Boolean, ok: Boolean) = TextView(this).apply {
            this.text = text; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(if (primary) bg else fg)
            typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); if (primary) setColor(fg) else setStroke(dp(1), Color.GRAY) }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { onAnswer(ok) }
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(bg) }
            addView(TextView(context).apply { text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg) })
            addView(TextView(context).apply { text = body; textSize = 14f; setTextColor(fg); maxLines = 12; setPadding(0, dp(8), 0, dp(14)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(button("不要", false, false))
                addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
                addView(button(action, true, true))
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM; dimAmount = 0.35f; horizontalMargin = 0f; y = dp(24) }
        val wrap = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0); addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        if (runCatching { wm.addView(wrap, lp) }.isSuccess) panel = wrap else onAnswer(false)
    }
}
