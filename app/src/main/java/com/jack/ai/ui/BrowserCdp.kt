package com.jack.ai.ui

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.WebView
import com.wangzan101.mika.core.cap.web.ElementFusion
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.cap.web.cdp.DirectTransport
import com.wangzan101.mika.cap.web.cdp.TargetMatch
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.shell.DevtoolsSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Kavi 浏览器的 CDP 输入适配。只负责输入，页面快照和工具参数仍沿用原来的实现。 */
internal object BrowserCdp {
    enum class Outcome { DONE, FALLBACK, UNCERTAIN }

    private val transport = DirectTransport()
    private val endpoint get() = DevtoolsSocket.name(Process.myPid())
    private val sessions = mutableMapOf<Int, CdpSession>()
    private val targets = mutableMapOf<Int, String>()
    private val locks = mutableMapOf<Int, Mutex>()
    private data class AxRef(
        val backendId: Int, val ownerId: Int?, val kind: String, val label: String,
        val tag: String, val type: String, val hits: Set<Int>, val frameId: String,
    )
    private data class KnownPage(val key: String, val viewport: ElementFusion.Box, val refs: Map<Int, AxRef>)
    private val pages = ConcurrentHashMap<Int, KnownPage>()
    @Volatile private var users = 0
    private var debuggingOn = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val backgroundClose = Runnable { disable() }

    /** 工具开始时打开；最后一个工具结束立即关掉 USB 可见的调试接口。主线程调用。 */
    fun begin() {
        handler.removeCallbacks(backgroundClose)
        users++
        if (!debuggingOn) {
            WebView.setWebContentsDebuggingEnabled(true)
            debuggingOn = true
        }
    }

    fun end() {
        if (users <= 0) return
        if (--users == 0) disable()
    }

    /** App 在后台超过半分钟时，不继续暴露登录中的网页。主线程调用。 */
    fun background() {
        if (debuggingOn) handler.postDelayed(backgroundClose, 30_000L)
    }

    fun foreground() { handler.removeCallbacks(backgroundClose) }

    private fun disable() {
        if (!debuggingOn) return
        BrowserNetwork.detachAll()
        sessions.values.forEach { it.close() }
        sessions.clear()
        targets.clear()
        WebView.setWebContentsDebuggingEnabled(false)
        debuggingOn = false
    }

    private suspend fun session(tab: JackBrowser.Tab): CdpSession? {
        if (users == 0 || !debuggingOn || tab.web == null) return null
        val lock = withContext(Dispatchers.Main) { locks.getOrPut(tab.id) { Mutex() } }
        return lock.withLock {
            sessions[tab.id]?.takeUnless { it.closed.value }?.let { return@withLock it }
            sessions.remove(tab.id)?.close()
            val view = withContext(Dispatchers.Main) { tab.web } ?: return@withLock null
            val marker = UUID.randomUUID().toString()
            val marked = try {
                withTimeoutOrNull(1_500) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Boolean> { c ->
                            view.evaluateJavascript(TargetMatch.markScript(marker)) { if (c.isActive) c.resume(true) }
                        }
                    }
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!marked) return@withLock null
            try {
                val url = withContext(Dispatchers.Main) { view.url }
                val claimed = targets.filterKeys { it != tab.id }.values.toSet()
                val candidates = TargetMatch.candidates(
                    CdpDiscovery.list(transport, endpoint, timeoutMs = 2_000), claimed, url)
                for (target in candidates) {
                    val s = runCatching {
                        CdpSession.connect(transport, endpoint,
                            CdpDiscovery.webSocketPath(target.webSocketDebuggerUrl!!), handshakeTimeoutMs = 1_500)
                    }.getOrNull() ?: continue
                    val found = runCatching {
                        s.evaluate(TargetMatch.READ_SCRIPT, timeoutMs = 1_500)["value"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    if (TargetMatch.isMarker(found, marker)) {
                        sessions[tab.id] = s
                        targets[tab.id] = target.id
                        return@withLock s
                    }
                    s.close()
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                withContext(Dispatchers.Main) { runCatching { view.evaluateJavascript(TargetMatch.CLEAR_SCRIPT, null) } }
            }
        }
    }

    /** 页面动作前订阅 Network；断线时仅放弃截接口，不影响浏览器输入。 */
    suspend fun observeNetwork(tab: JackBrowser.Tab) {
        val connected = try {
            session(tab)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (connected != null) BrowserNetwork.start(tab, connected)
    }

    /** 工具收尾前取响应正文，关 CDP 后只保留内存中的有限缓存。 */
    suspend fun flushNetwork(tabId: Int? = null) = BrowserNetwork.flush(tabId)

    /** 事件发出后断线，页面可能已执行：停止重复发送；只有尚未发出输入时才退回旧脚本。 */
    private suspend fun perform(tab: JackBrowser.Tab, action: String, block: suspend CdpInputScript.() -> Unit): Outcome {
        val s = session(tab)
        if (s == null) {
            tab.diag.add("CDP 连不上，$action 退回旧输入")
            return Outcome.FALLBACK
        }
        var eventAttempted = false
        val input = CdpInputScript(send = { method, params ->
            // 单纯移动鼠标失败时，没有发生点击或编辑，可以退回脚本。
            if (method != "Input.dispatchMouseEvent" ||
                params["type"]?.jsonPrimitive?.contentOrNull != "mouseMoved") eventAttempted = true
            s.send(method, params, timeoutMs = 2_000)
        })
        return try {
            input.block()
            tab.diag.add("CDP 输入成功：$action")
            Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessions.remove(tab.id)?.close()
            targets.remove(tab.id)
            val status = failureOutcome(eventAttempted)
            tab.diag.add("CDP $action 失败：${e.javaClass.simpleName}，" +
                if (status == Outcome.FALLBACK) "退回旧输入" else "不重复输入")
            status
        }
    }

    /** 页面读完脚本快照后，用无障碍树补漏；掉线时原样返回脚本结果。 */
    suspend fun enrich(tab: JackBrowser.Tab, page: JackBrowser.Page, key: String,
                       hidden: List<ElementFusion.Box>, listeners: List<ElementProbe.Listener>,
                       reserve: suspend (Int) -> Int?): JackBrowser.Page {
        val s = session(tab) ?: return page.also { pages.remove(tab.id) }
        val source = page.items.map {
            ElementFusion.PageNode(it.n, it.kind, it.label,
                ElementFusion.Box(it.x.toDouble(), it.y.toDouble(),
                    (it.x + it.w).toDouble(), (it.y + it.h).toDouble()))
        }
        val viewport = ElementFusion.Box(page.vx.toDouble(), page.vy.toDouble(),
            (page.vx + page.vw).toDouble(), (page.vy + page.vh).toDouble())
        val probe = ElementProbe(s).read(source, hidden, listeners, viewport)
            ?: return page.also { pages.remove(tab.id); tab.diag.add("CDP 元素融合失败，保留脚本清单") }
        val visible = source.withIndex().filter { it.value.n !in probe.coveredPageNumbers }
        val fused = ElementFusion.fuse(visible.map { it.value },
            visible.map { probe.pageIds[it.index] }, probe.ax, probe.extras, viewport)
        val prev = pages[tab.id]?.takeIf { it.key == key }?.refs.orEmpty()
        val oldByBackend = prev.entries.associate { it.value.backendId to it.key }
        val added = fused.elements.filter { it.oldN == null }
        val need = added.mapNotNull { it.backendId }.distinct().filterNot { it in oldByBackend }
        val first = if (need.isEmpty()) 0 else reserve(need.size)
            ?: return page.also { pages.remove(tab.id); tab.diag.add("CDP 元素编号没能分配，保留脚本清单") }
        val assigned = oldByBackend.toMutableMap()
        need.forEachIndexed { i, backend -> assigned[backend] = first + i }
        val previous = page.items.associateBy { it.n }
        val extraById = probe.extras.associateBy { it.backendId }
        val refs = HashMap<Int, AxRef>()
        val items = fused.elements.mapNotNull { f ->
            if (f.oldN != null) {
                previous[f.oldN]?.copy(kind = f.kind, label = f.label)
            } else {
                val id = f.backendId ?: return@mapNotNull null
                val n = assigned[id] ?: return@mapNotNull null
                val e = extraById[id] ?: return@mapNotNull null
                refs[n] = AxRef(id, e.ownerId, f.kind, f.label, e.tag, e.type, e.hits, e.frameId)
                JackBrowser.Item(n, f.kind, f.label, null, null, emptyList(), null,
                    f.box.l.toFloat(), f.box.t.toFloat(), f.box.width.toFloat(), f.box.height.toFloat())
            }
        }
        pages[tab.id] = KnownPage(key, viewport, refs)
        tab.diag.add("CDP 元素融合：${probe.stats}，新增 ${refs.size}")
        return page.copy(items = items)
    }

    /** 只有无障碍树有的元素没有 data-jack 标记，先查缓存再走旧脚本。 */
    fun hasRef(tab: JackBrowser.Tab, n: Int) = pages[tab.id]?.refs?.containsKey(n) == true

    fun inspect(tab: JackBrowser.Tab, n: Int): JsonObject? {
        val ref = pages[tab.id]?.refs?.get(n) ?: return null
        val password = ref.kind == "密码框" || ref.type == "password"
        val text = !password && ref.kind in setOf("输入框", "搜索框")
        return buildJsonObject {
            put("found", true); put("n", n); put("kind", ref.kind); put("label", ref.label)
            put("type", ref.type); put("tag", ref.tag)
            put("password", password); put("text", text)
            put("frame", ref.ownerId != null)
            put("file", ref.type == "file")
            put("submit", ref.tag == "button" && ref.type == "submit")
        }
    }

    fun markText(tab: JackBrowser.Tab, query: String): JsonObject? {
        val wanted = query.trim()
        if (wanted.isBlank()) return null
        val refs = pages[tab.id]?.refs.orEmpty()
        val exact = refs.entries.firstOrNull { it.value.label == wanted }
        val found = exact ?: refs.entries.firstOrNull { it.value.label.contains(wanted, ignoreCase = true) }
        return found?.let { inspect(tab, it.key) }
    }

    /** 新增元素点之前重新滚动、取框、确认未被遮住，不靠旧快照的坐标盲点。 */
    suspend fun locate(tab: JackBrowser.Tab, n: Int): Pair<Double, Double>? {
        val page = pages[tab.id] ?: return null
        val ref = page.refs[n] ?: return null
        val s = session(tab) ?: return null
        val place = ElementProbe(s).locate(ref.backendId, ref.ownerId, page.viewport, ref.frameId, ref.hits) ?: return null
        if (place.hit !in ref.hits && place.hit != ref.ownerId) return null
        return place.x to place.y
    }

    /** AX 新增的输入框按键前先获得焦点，不通过鼠标点击提交按钮。 */
    suspend fun focus(tab: JackBrowser.Tab, n: Int): Boolean {
        val ref = pages[tab.id]?.refs?.get(n) ?: return false
        val s = session(tab) ?: return false
        return runCatching { s.send("DOM.focus", buildJsonObject {
            put("backendNodeId", ref.backendId)
        }, timeoutMs = 1_000) }.isSuccess
    }

    fun forget(tab: JackBrowser.Tab) { pages.remove(tab.id) }

    fun release(tab: JackBrowser.Tab) {
        pages.remove(tab.id)
        sessions.remove(tab.id)?.close()
        targets.remove(tab.id)
        locks.remove(tab.id)
    }

    internal fun failureOutcome(eventAttempted: Boolean) = if (eventAttempted) Outcome.UNCERTAIN else Outcome.FALLBACK

    suspend fun act(tab: JackBrowser.Tab, x: Double, y: Double, op: String, arg: String, clear: Boolean): Outcome {
        if (op == "key" && BrowserCdpKeys.named(arg) == null) return Outcome.FALLBACK
        return perform(tab, op) { act(x, y, op, arg, clear) }
    }

    suspend fun at(tab: JackBrowser.Tab, x: Double, y: Double, x2: Double, y2: Double, durationMs: Long): Outcome =
        perform(tab, if (x == x2 && y == y2) "tap" else "swipe") {
            at(x, y, x2, y2, durationMs)
        }
}

internal object BrowserCdpCoords {
    fun fromScreenshot(pixel: Float, viewWidth: Int, cssWidth: Float): Double {
        val imageScale = minOf(1f, 720f / viewWidth.coerceAtLeast(1))
        return (pixel / imageScale * cssWidth / viewWidth.coerceAtLeast(1)).toDouble()
    }

    fun center(x: Float, y: Float, w: Float, h: Float): Pair<Double, Double> =
        (x + w / 2).toDouble() to (y + h / 2).toDouble()
}
