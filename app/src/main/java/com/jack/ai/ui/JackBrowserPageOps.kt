package com.jack.ai.ui

import kotlinx.serialization.builtins.serializer
import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.http.SslError
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.BrowserAds
import com.jack.ai.api.BrowserPages
import com.jack.ai.api.BrowserTranslate
import com.jack.ai.api.MediaGrab
import com.jack.ai.data.Downloads
import com.jack.ai.data.backgroundEndpoint
import com.wangzan101.mika.core.cap.web.ElementFusion
import com.wangzan101.mika.core.cap.web.ElementProbe
import kotlinx.serialization.json.put
import com.jack.ai.api.Net
import com.jack.ai.data.ProxyCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import com.jack.ai.ui.JackBrowser.Page
import com.jack.ai.ui.JackBrowser.Item
import com.jack.ai.ui.JackBrowser.Tab
import com.jack.ai.ui.JackBrowser.PinTab

// Kavi 操作网页：读页面、点、填、滑、截图、等加载（从 JackBrowser.kt 拆出来，是 JackBrowser 的扩展函数）。

/** 没放进任何界面时，按手机屏幕大小排版，网页才有正常的宽高。 */
internal fun JackBrowser.ensureLaidOut(w: WebView) {
    if (w.parent != null && w.width > 0) return
    val dm = app.resources.displayMetrics
    w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
    w.layout(0, 0, dm.widthPixels, dm.heightPixels)
}

internal suspend fun JackBrowser.eval(ctx: Context, js: String): String = withContext(Dispatchers.Main) {
    val w = pinned()?.web ?: webView(ctx)
    ensureLaidOut(w)
    suspendCancellableCoroutine { cont -> w.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "null") } }
}

/** evaluateJavascript 把返回的字符串再包了一层 JSON 引号，解开。 */
internal fun JackBrowser.unwrap(r: String): String = runCatching { (json.parseToJsonElement(r) as? JsonPrimitive)?.content }.getOrNull() ?: r

/** 读当前页面：可操作元素编上号，加上当前屏附近的文字。 */
internal suspend fun JackBrowser.read(ctx: Context): Page {
    agentAt = System.currentTimeMillis()
    val t = pinned() ?: tab
    val raw = unwrap(eval(ctx, BrowserScripts.snapshot(ctx)))
    val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return Page(address, title, emptyList(), 0, 0, 0, 0, 0, "", 0f, 0f, 0f,
            error ?: "读不到页面内容（页面可能还没加载出来）", null).also {
            BrowserPacing.report(t, it.url, it.title, it.text, t.mainHttpStatus, emptyList(),
                BrowserNetwork.risk(t.id, it.url))
        }
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
    fun JsonObject.f(k: String) = s(k).toFloatOrNull() ?: 0f
    val items = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { e ->
        val i = e.jsonObject
        Item(
            i.s("n").toIntOrNull() ?: 0, i.s("kind"), i.s("label"), (i["value"] as? JsonPrimitive)?.content,
            (i["checked"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            (i["options"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as JsonPrimitive).content },
            (i["href"] as? JsonPrimitive)?.content, i.f("x"), i.f("y"), i.f("w"), i.f("h"),
        )
    }
    val dialog = lastDialog.also { lastDialog = null }
    val page = Page(o.s("url"), o.s("title"), items, o.f("above").toInt(), o.f("below").toInt(), o.f("scroll").toInt(),
        o.f("height").toInt(), o.f("vh").toInt(), o.s("text"), o.f("vx"), o.f("vy"), o.f("vw"),
        withContext(Dispatchers.Main) { error }, dialog, pendingHere(), o.f("frames").toInt())
    val frames = (o["frameUrls"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content }
    BrowserPacing.report(t, page.url, page.title, page.text, t.mainHttpStatus, frames,
        BrowserNetwork.risk(t.id, page.url),
        longPage = o.s("textLength").toIntOrNull()?.let { it > 800 } ?: (page.height > page.vh * 2))
    // 后台、没有 CDP 或页面已经跳转时维持脚本清单，避免影响现有读页。
    if (o.s("docKey").isBlank() || !withContext(Dispatchers.Main) { t.web?.isAttachedToWindow == true }) return page
    fun box(it: JsonObject): ElementFusion.Box {
        fun n(k: String) = (it[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        return ElementFusion.Box(n("x"), n("y"), n("x") + n("w"), n("y") + n("h"))
    }
    val hidden = (o["hidden"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.map(::box)
    val listeners = (o["probes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.map {
            ElementProbe.Listener((it["label"] as? JsonPrimitive)?.content.orEmpty(), box(it))
        }
    return BrowserCdp.enrich(t, page, o.s("docKey"), hidden, listeners) { count ->
        unwrap(eval(ctx, BrowserScripts.reserve(ctx, o.s("docKey"), count))).toIntOrNull()
    }
}

/** 某个编号的元素是什么（决定要不要先问用户）。找不到返回 null。 */
internal suspend fun JackBrowser.inspect(ctx: Context, n: Int): JsonObject? {
    val t = pinned() ?: tab
    if (BrowserCdp.hasRef(t, n)) return BrowserCdp.inspect(t, n)
    val raw = unwrap(eval(ctx, BrowserScripts.inspect(ctx, n)))
    return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
}

/** 对某个编号的元素做动作；CDP 不可用时沿用脚本输入。 */
internal suspend fun JackBrowser.act(ctx: Context, n: Int, op: String, arg: String = "", clear: Boolean = false): String? {
    agentAt = System.currentTimeMillis()
    withContext(Dispatchers.Main) { error = null; pageDone = CompletableDeferred() }
    val t = pinned() ?: tab
    if (BrowserPacing.blocked(t) != null) return "网站已触发风控或验证，停止操作。请让用户接管后重新读页。"
    if (op == "click" || op == "select" || (op == "key" && arg == "enter")) BrowserPacing.before(t)
    BrowserCdp.observeNetwork(t)
    if (BrowserCdp.hasRef(t, n)) {
        if (op !in setOf("click", "hover", "type", "delete", "key")) {
            return "这个编号来自嵌入页面或封闭组件，暂不支持 $op；请先点开再用编号操作。"
        }
        if (!withContext(Dispatchers.Main) { t.web?.isAttachedToWindow == true }) {
            return "这个嵌入元素目前在后台，不能安全地发真实输入。请先打开它所在标签页。"
        }
        val point = BrowserCdp.locate(t, n) ?: return "编号 $n 已经移走或被挡住，请重新 read。"
        if (op == "key" && !BrowserCdp.focus(t, n)) return "这个元素现在无法获得输入焦点，请重新 read。"
        val sent = BrowserCdp.act(t, point.first, point.second, op, arg, clear)
        t.lastCdpInput = sent == BrowserCdp.Outcome.DONE
        if (sent == BrowserCdp.Outcome.UNCERTAIN) {
            return "CDP 输入中途断开，页面可能已经收到操作。先读页面确认，不要直接重复。"
        }
        if (sent == BrowserCdp.Outcome.FALLBACK) {
            return "CDP 已断开，这个元素没有脚本入口，请重新 read 再试。"
        }
        settle()
        return withContext(Dispatchers.Main) { error }
    }
    var outcome = BrowserCdp.Outcome.FALLBACK
    if (op in setOf("click", "hover", "type", "delete", "key")) {
        // 日期等特殊原生控件仍按原方式设置，普通文本框才用 CDP 输入。
        val info = if (op == "type" || op == "delete") inspect(ctx, n) else null
        val fieldType = (info?.get("type") as? JsonPrimitive)?.content.orEmpty()
        val isText = (info?.get("text") as? JsonPrimitive)?.content == "true"
        val supported = op !in setOf("type", "delete") || (isText &&
            (op != "type" || fieldType !in setOf("date", "datetime-local", "time", "month", "week", "range", "color")))
        // 没挂在界面上的网页（后台标签页）收不收得到 CDP 输入还没在真机上验证过，先走原来的脚本
        val attached = withContext(Dispatchers.Main) { (pinned() ?: tab).web?.isAttachedToWindow == true }
        if (supported && attached) {
            val at = runCatching {
                json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.center(ctx, n)))).jsonObject
            }.getOrNull()
            if ((at?.get("found") as? JsonPrimitive)?.content == "true") {
                fun coord(k: String) = (at[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                delay(300)
                val focused = op != "key" || unwrap(eval(ctx, BrowserScripts.focus(ctx, n))) == "true"
                if (focused) outcome = BrowserCdp.act(pinned() ?: tab, coord("x"), coord("y"), op, arg, clear)
            }
        }
    }
    (pinned() ?: tab).lastCdpInput = outcome == BrowserCdp.Outcome.DONE
    if (outcome == BrowserCdp.Outcome.UNCERTAIN) {
        return "CDP 输入中途断开，网页可能已经收到这次操作。先读页面确认，不要直接重复提交。"
    }
    val err = if (outcome == BrowserCdp.Outcome.DONE) null else {
        val raw = unwrap(eval(ctx, BrowserScripts.act(ctx, n, op, arg, clear)))
        runCatching { (json.parseToJsonElement(raw).jsonObject["err"] as? JsonPrimitive)?.content }.getOrNull()
    }
    settle()
    return err ?: withContext(Dispatchers.Main) { error }
}

/** 按文字找元素，标成编号 0，返回它的情况（和 inspect 一样）；找不到返回 null。 */
internal suspend fun JackBrowser.markText(ctx: Context, text: String): JsonObject? {
    agentAt = System.currentTimeMillis()
    val raw = unwrap(eval(ctx, BrowserScripts.markText(ctx, text)))
    return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?.takeIf { (it["found"] as? JsonPrimitive)?.content == "true" }
        ?: BrowserCdp.markText(pinned() ?: tab, text)
}

/**
 * 像手指一样在网页窗口上点或划（真的触摸事件）：网页自己画的控件、嵌在里面的别的网站（登录框、支付框）、
 * 要滑动的组件都认。坐标是截图（look）上的像素。网页窗口不在屏幕上时退回用脚本点那个位置。
 */
internal suspend fun JackBrowser.touch(ctx: Context, x1: Float, y1: Float, x2: Float = x1, y2: Float = y1, durationMs: Long = 60): String? {
    agentAt = System.currentTimeMillis()
    val w = withContext(Dispatchers.Main) { (pinned()?.web ?: webView(ctx)).also { ensureLaidOut(it) } }
    val t = pinned() ?: tab
    if (BrowserPacing.blocked(t) != null) return "网站已触发风控或验证，停止触摸。请用户接管。"
    BrowserPacing.before(t)
    BrowserCdp.observeNetwork(t)
    val scale = withContext(Dispatchers.Main) { minOf(1f, 720f / w.width.coerceAtLeast(1)) }
    val (ax, ay, bx, by) = listOf(x1 / scale, y1 / scale, x2 / scale, y2 / scale)
    val inside = withContext(Dispatchers.Main) { listOf(ax, bx).all { it in 0f..w.width.toFloat() } && listOf(ay, by).all { it in 0f..w.height.toFloat() } }
    if (!inside) return "坐标在网页窗口外面（截图大约 ${(w.width * scale).toInt()}×${(w.height * scale).toInt()}）"
    withContext(Dispatchers.Main) { pageDone = CompletableDeferred(); clearError() }
    val isSwipe = x1 != x2 || y1 != y2
    val mark = if (isSwipe) BrowserNetwork.mark(t.id) else null
    val cssWidth = unwrap(eval(ctx, "innerWidth")).toFloatOrNull()?.takeIf { it > 0 } ?: w.width.toFloat()
    val outcome = if (!withContext(Dispatchers.Main) { w.isAttachedToWindow }) BrowserCdp.Outcome.FALLBACK
    else BrowserCdp.at(pinned() ?: tab,
        BrowserCdpCoords.fromScreenshot(x1, w.width, cssWidth),
        BrowserCdpCoords.fromScreenshot(y1, w.width, cssWidth),
        BrowserCdpCoords.fromScreenshot(x2, w.width, cssWidth),
        BrowserCdpCoords.fromScreenshot(y2, w.width, cssWidth), durationMs)
    if (outcome == BrowserCdp.Outcome.UNCERTAIN) {
        return "CDP 输入中途断开，网页可能已经收到这次操作。先读页面确认，不要直接重复。"
    }
    if (outcome == BrowserCdp.Outcome.FALLBACK) {
        if (withContext(Dispatchers.Main) { w.isAttachedToWindow }) {
            withContext(Dispatchers.Main) { gesture(w, ax, ay, bx, by, durationMs) }
        } else {
            // 不在屏幕上：触摸事件不一定送得到，退回脚本（只能点，不能划）
            val k = w.width.toFloat() / cssWidth
            eval(ctx, "(function(){var e=document.elementFromPoint(${ax / k},${ay / k});if(e){e.click();}})();1")
        }
    }
    settle()
    if (isSwipe && BrowserNetwork.loadedMore(t.id, mark)) BrowserPacing.loadedMore(t)
    return withContext(Dispatchers.Main) { error }
}

/** 按下、（划过去）、抬起。在主线程调。 */
internal suspend fun JackBrowser.gesture(w: WebView, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
    val steps = if (x1 == x2 && y1 == y2) 1 else 12
    val t0 = android.os.SystemClock.uptimeMillis()
    fun send(action: Int, x: Float, y: Float, at: Long) {
        val e = android.view.MotionEvent.obtain(t0, at, action, x, y, 0)
        e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        w.dispatchTouchEvent(e)
        e.recycle()
    }
    send(android.view.MotionEvent.ACTION_DOWN, x1, y1, t0)
    for (i in 1..steps) {
        delay(durationMs / steps)
        val f = i.toFloat() / steps
        send(android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, android.os.SystemClock.uptimeMillis())
    }
    send(android.view.MotionEvent.ACTION_UP, x2, y2, android.os.SystemClock.uptimeMillis())
}

/** 截图上这个位置是什么（动手前看要不要问用户）：和 inspect 一样的信息，或 frame=true（读不到的嵌入框）。 */
internal suspend fun JackBrowser.elementAt(ctx: Context, x: Float, y: Float): JsonObject? {
    val (cx, cy) = withContext(Dispatchers.Main) {
        val w = webView(ctx)
        val scale = minOf(1f, 720f / w.width.coerceAtLeast(1))
        x / scale to y / scale
    }
    val k = withContext(Dispatchers.Main) { webView(ctx).width.toFloat() } / unwrap(eval(ctx, "innerWidth")).toFloat().coerceAtLeast(1f)
    return runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.at(ctx, cx / k, cy / k)))).jsonObject }.getOrNull()
}

/** 编号 n 的元素中心换成截图上的像素（先滚到屏幕中间）。找不到返回 null。 */
internal suspend fun JackBrowser.centerOf(ctx: Context, n: Int): Pair<Float, Float>? {
    val t = pinned() ?: tab
    if (BrowserCdp.hasRef(t, n)) {
        val center = BrowserCdp.locate(t, n) ?: return null
        val w = withContext(Dispatchers.Main) { t.web ?: webView(ctx) }
        val cssW = unwrap(eval(ctx, "innerWidth")).toFloatOrNull()?.coerceAtLeast(1f) ?: w.width.toFloat()
        val k = w.width / cssW * minOf(1f, 720f / w.width.coerceAtLeast(1))
        return (center.first * k).toFloat() to (center.second * k).toFloat()
    }
    val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.center(ctx, n)))).jsonObject }.getOrNull() ?: return null
    if ((o["found"] as? JsonPrimitive)?.content != "true") return null
    fun f(k: String) = (o[k] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
    delay(300)
    return withContext(Dispatchers.Main) {
        val w = webView(ctx)
        val k = w.width / f("vw").coerceAtLeast(1f)
        val scale = minOf(1f, 720f / w.width.coerceAtLeast(1))
        f("x") * k * scale to f("y") * k * scale
    }
}

/** 页面有没有变：网址 + 文字长度 + 元素个数（点了以后比一比，看点没点上）。 */
internal suspend fun JackBrowser.pageSignature(ctx: Context): String {
    val url = withContext(Dispatchers.Main) { (pinned() ?: tab).address }
    return url + "|" + unwrap(eval(ctx, BrowserScripts.fingerprint(ctx)))
}

/** 等网页里出现这段文字（最多 timeoutMs）；text 为空就等页面不再变化。返回等到没有。 */
internal suspend fun JackBrowser.waitFor(ctx: Context, text: String, timeoutMs: Long): Boolean {
    agentAt = System.currentTimeMillis()
    if (text.isBlank()) return waitStable(ctx, timeoutMs)
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (unwrap(eval(ctx, BrowserScripts.hasText(ctx, text))) == "true") return true
        delay(500)
    }
    return false
}

/** 等页面加载完、内容不再变（连着两次一样）。返回在时限内稳没稳下来。 */
internal suspend fun JackBrowser.waitStable(ctx: Context, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    var last = ""
    var same = 0
    while (System.currentTimeMillis() < end) {
        val sig = unwrap(eval(ctx, BrowserScripts.signature(ctx)))
        val busy = withContext(Dispatchers.Main) { (pinned() ?: tab).loading < 100 }
        if (!busy && sig == last) { if (++same >= 2) return true } else same = 0
        last = sig
        delay(400)
    }
    return false
}

/** 读整页：what = text / links / table。返回网页给的 JSON。 */
internal suspend fun JackBrowser.extract(ctx: Context, what: String, start: Int = 0, limit: Int = 12_000): JsonObject? {
    agentAt = System.currentTimeMillis()
    val fromPage = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.extract(ctx, what, start, limit)))).jsonObject }.getOrNull()
    if (what != "table") return fromPage
    val t = pinned() ?: tab
    // 并行比价会在同一次工具调用里抽取；先取回本标签已完成的接口正文。
    BrowserCdp.flushNetwork(t.id)
    val fromNet = BrowserNetwork.table(t.id, t.address) ?: return fromPage
    // 接口数据只有名称/价格/店铺/链接四列，也可能是推荐流这类无关列表：网页上读得到像样的表就还用网页的，
    // 网页读不到或明显少一大截（虚拟列表、画在画布上）才换接口数据
    val pageRows = tableRows(fromPage)
    if (pageRows > 0 && tableRows(fromNet) < pageRows * 2) return fromPage
    t.diag.add("CDP 接口列表用于 extract（网页脚本读到 $pageRows 行）")
    return fromNet
}

/** extract(table) 结果里最多的一张表或第一组列表有几行。 */
internal fun JackBrowser.tableRows(o: JsonObject?): Int {
    o ?: return 0
    val tables = (o["tables"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .maxOfOrNull { ((it as? JsonObject)?.get("rows") as? kotlinx.serialization.json.JsonArray)?.size ?: 0 } ?: 0
    val list = ((o["lists"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonArray)?.size ?: 0
    return if (tables > 0) tables else list
}

/**
 * 上传对话文件夹里的文件：用手指点编号 n 的上传按钮（网页只认真的点击才肯弹选文件），
 * 网页要文件时把这个文件交过去。返回出错原因，成功返回 null。
 */
internal suspend fun JackBrowser.upload(ctx: Context, n: Int, file: java.io.File): String? {
    val (x, y) = centerOf(ctx, n) ?: return "找不到编号 $n，页面可能变了"
    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
    uploadTaken = false
    agentUpload = uri
    try {
        touch(ctx, x, y)?.let { return it }
        val end = System.currentTimeMillis() + 3000
        while (!uploadTaken && System.currentTimeMillis() < end) delay(200)
        if (!uploadTaken) return "点了编号 $n，但网页没有要文件（可能不是上传按钮，或者网页窗口不在屏幕上）。可以 look 看看上传按钮在哪，或者请用户自己传。"
        settle()
        return null
    } finally {
        agentUpload = null
    }
}

internal fun JackBrowser.pin(t: Tab) = PinTab(t)

internal suspend fun JackBrowser.pinned(): Tab? = kotlin.coroutines.coroutineContext[PinTab]?.t

/** 关掉某个标签页（按对象找，并行抓完收拾用）。 */
internal fun JackBrowser.closeTab(t: Tab) { tabs.indexOf(t).takeIf { it >= 0 }?.let { closeTab(it) } }

/** 标签页清单（给 Kavi 看）：第几个、标题、网址、是不是当前。 */
internal fun JackBrowser.tabList(): String = tabs.mapIndexed { i, t ->
    "${i + 1}. ${t.badge}${t.title.ifBlank { if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address) }} ${t.address}" + if (t === tab) "（当前）" else ""
}.joinToString("\n")

/** 关掉挡着网页的弹窗（下 App、领券、Cookie 提示），返回关掉了什么。 */
internal suspend fun JackBrowser.dismissPopups(ctx: Context): List<String> {
    val raw = unwrap(eval(ctx, BrowserScripts.dismiss(ctx)))
    val closed = runCatching { (json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
    if (closed.isNotEmpty()) delay(300)
    return closed
}

/** 滚到页面最底下（往下划才加载更多的页面用），等新内容出来。 */
internal suspend fun JackBrowser.scrollToBottom(ctx: Context) {
    agentAt = System.currentTimeMillis()
    val t = pinned() ?: tab
    if (BrowserPacing.blocked(t) != null) return
    BrowserPacing.before(t)
    BrowserCdp.observeNetwork(t)
    val mark = BrowserNetwork.mark(t.id)
    eval(ctx, "window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)); 1")
    delay(600)
    waitStable(ctx, 5_000)
    if (BrowserNetwork.loadedMore(t.id, mark)) BrowserPacing.loadedMore(t)
}

/** 翻页：dir = 1 往下，-1 往上。 */
internal suspend fun JackBrowser.scroll(ctx: Context, dir: Int) {
    agentAt = System.currentTimeMillis()
    val t = pinned() ?: tab
    if (BrowserPacing.blocked(t) != null) return
    if (dir > 0) BrowserPacing.before(t)
    BrowserCdp.observeNetwork(t)
    val mark = if (dir > 0) BrowserNetwork.mark(t.id) else null
    eval(ctx, "window.scrollBy(0, ${dir} * window.innerHeight * 0.8); 1")
    delay(400)
    if (dir > 0) runCatching { waitNetQuiet(ctx, 2_000) }
    if (dir > 0 && BrowserNetwork.loadedMore(t.id, mark)) BrowserPacing.loadedMore(t)
}

internal suspend fun JackBrowser.back(ctx: Context): Boolean {
    agentAt = System.currentTimeMillis()
    val t = pinned() ?: tab
    if (BrowserPacing.blocked(t) != null) return false
    BrowserPacing.before(t)
    BrowserCdp.observeNetwork(t)
    val ok = withContext(Dispatchers.Main) {
        val w = webView(ctx)
        if (w.canGoBack()) { pageDone = CompletableDeferred(); clearError(); w.goBack(); true } else false
    }
    if (ok) settle()
    return ok
}

/** 动作做完等一会儿：如果引起了跳转就等新页面加载完（最多 15 秒），再给脚本一点时间把内容画出来。 */
internal suspend fun JackBrowser.settle() {
    delay(500)
    val pending = withContext(Dispatchers.Main) { pageDone?.takeIf { loading < 100 } }
    if (pending != null) withTimeoutOrNull(15_000) { pending.await() }
    // 很多网页加载完还在慢慢往里填内容：等它不再变（最多 4 秒）
    runCatching { waitStable(app, 4_000) }
    // 后台请求还没回来的话，数据可能还没刷新：再等它们回来（最多 5 秒，长连接不等）
    runCatching { waitNetQuiet(app, 5_000) }
}

/** 等网页的后台请求都回来。钩子没装上就不等。 */
internal suspend fun JackBrowser.waitNetQuiet(ctx: Context, timeoutMs: Long) {
    val end = System.currentTimeMillis() + timeoutMs
    var waited = false
    while (System.currentTimeMillis() < end) {
        val (inflight, _) = BrowserNetWatch.state(eval(ctx, BrowserNetWatch.STATE_JS)) ?: return
        if (inflight == 0) { if (waited) runCatching { waitStable(ctx, 1_500) }; return }
        waited = true
        delay(300)
    }
}

/** 现在的请求失败序号（动作前记一下，做完用 [netFailsSince] 取这期间失败的）。钩子没装上是 null。 */
internal suspend fun JackBrowser.netSeq(ctx: Context): Int? = runCatching { BrowserNetWatch.state(eval(ctx, BrowserNetWatch.STATE_JS))?.second }.getOrNull()

internal suspend fun JackBrowser.netFailsSince(ctx: Context, seq: Int): List<BrowserNetWatch.Fail> =
    runCatching { BrowserNetWatch.fails(eval(ctx, BrowserNetWatch.failsSinceJs(seq))) }.getOrDefault(emptyList())

/**
 * 这一步做完的网页截个小图（不画编号框），存成 JPEG，对话里按步骤显示。网页没画出来就不存，返回 false。
 */
internal suspend fun JackBrowser.snapshot(ctx: Context, out: java.io.File, maxWidth: Int = 480): Boolean {
    val bmp = withContext(Dispatchers.Main) {
        val w = webView(ctx)
        ensureLaidOut(w)
        if (w.width <= 0 || w.height <= 0) return@withContext null
        val scale = minOf(1f, maxWidth.toFloat() / w.width)
        val b = Bitmap.createBitmap((w.width * scale).toInt(), (w.height * scale).toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(b)
        c.scale(scale, scale)
        c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
        w.draw(c)
        val sample = IntArray(16) { i -> b.getPixel((b.width * (i % 4 + 1) / 5).coerceAtMost(b.width - 1), (b.height * (i / 4 + 1) / 5).coerceAtMost(b.height - 1)) }
        if (sample.distinct().size == 1) { b.recycle(); null } else b
    } ?: return false
    return withContext(Dispatchers.IO) {
        try {
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            true
        } catch (e: Exception) {
            false
        } finally {
            bmp.recycle()
        }
    }
}

/**
 * 截图（画上和清单一样的编号框），缩到最宽 720 像素的 JPEG，返回 data URL。
 * 网页窗口没放在任何界面里时可能截不出东西，返回 null。
 */
internal suspend fun JackBrowser.screenshot(ctx: Context, page: Page): String? = withContext(Dispatchers.Main) {
    val w = webView(ctx)
    ensureLaidOut(w)
    if (w.width <= 0 || w.height <= 0) return@withContext null
    val scale = minOf(1f, 720f / w.width)
    val bmp = Bitmap.createBitmap((w.width * scale).toInt(), (w.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(scale, scale)
    // 网页内容自己会按滚动位置画；画框的坐标是相对可视区的
    c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
    w.draw(c)
    c.translate(w.scrollX.toFloat(), w.scrollY.toFloat())
    // 全白（或全一色）就是没画出来
    val sample = IntArray(16) { i -> bmp.getPixel((bmp.width * (i % 4 + 1) / 5).coerceAtMost(bmp.width - 1), (bmp.height * (i / 4 + 1) / 5).coerceAtMost(bmp.height - 1)) }
    if (sample.distinct().size == 1) { bmp.recycle(); return@withContext null }
    // 元素坐标（相对网页可视区左上角的 CSS px）→ 网页窗口像素
    val k = if (page.vw > 0) w.width / page.vw else 1f
    val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f / scale; color = Color.rgb(230, 40, 40) }
    val tag = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(230, 40, 40) }
    val txt = Paint().apply { color = Color.WHITE; textSize = 26f / scale; isAntiAlias = true; isFakeBoldText = true }
    page.items.forEach { i ->
        val l = (i.x - page.vx) * k; val t = (i.y - page.vy) * k
        if (t > w.height || t + i.h * k < 0) return@forEach
        c.drawRect(l, t, l + i.w * k, t + i.h * k, box)
        val label = i.n.toString()
        val tw = txt.measureText(label) + 8f / scale
        c.drawRect(l, t, l + tw, t + txt.textSize + 6f / scale, tag)
        c.drawText(label, l + 4f / scale, t + txt.textSize, txt)
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
    bmp.recycle()
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
