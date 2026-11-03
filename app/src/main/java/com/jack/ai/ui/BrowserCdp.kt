package com.jack.ai.ui

import android.os.Process
import android.webkit.WebView
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/** Kavi 浏览器的 CDP 输入适配。只负责输入，页面快照和工具参数仍沿用原来的实现。 */
internal object BrowserCdp {
    enum class Outcome { DONE, FALLBACK, UNCERTAIN }

    private val transport = DirectTransport()
    private val endpoint get() = DevtoolsSocket.name(Process.myPid())
    private val sessions = mutableMapOf<Int, CdpSession>()
    private val targets = mutableMapOf<Int, String>()
    private val locks = mutableMapOf<Int, Mutex>()
    private var users = 0

    /** 工具开始时打开；最后一个工具结束立刻关掉 USB 可见的调试接口。主线程调用。 */
    fun begin() {
        if (users++ == 0) WebView.setWebContentsDebuggingEnabled(true)
    }

    fun end() {
        if (users <= 0 || --users != 0) return
        sessions.values.forEach { it.close() }
        sessions.clear()
        targets.clear()
        WebView.setWebContentsDebuggingEnabled(false)
    }

    private suspend fun session(tab: JackBrowser.Tab): CdpSession? {
        if (users == 0 || tab.web == null) return null
        val lock = withContext(Dispatchers.Main) { locks.getOrPut(tab.id) { Mutex() } }
        return lock.withLock {
            sessions[tab.id]?.takeUnless { it.closed.value }?.let { return@withLock it }
            sessions.remove(tab.id)?.close()
            val view = withContext(Dispatchers.Main) { tab.web } ?: return@withLock null
            val marker = UUID.randomUUID().toString()
            val marked = withTimeoutOrNull(1_500) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Boolean> { c ->
                        view.evaluateJavascript(TargetMatch.markScript(marker)) { if (c.isActive) c.resume(true) }
                    }
                }
            } ?: false
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
                withContext(Dispatchers.Main) { view.evaluateJavascript(TargetMatch.CLEAR_SCRIPT, null) }
            }
        }
    }

    /** 事件发出后断线，页面可能已执行：停止重复发送；只有尚未发出输入时才退回旧脚本。 */
    private suspend fun perform(tab: JackBrowser.Tab, action: String, block: suspend Sender.() -> Unit): Outcome {
        val s = session(tab)
        if (s == null) {
            tab.diag.add("CDP 连不上，$action 退回旧输入")
            return Outcome.FALLBACK
        }
        val sender = Sender(s)
        return try {
            sender.block()
            tab.diag.add("CDP 输入成功：$action")
            Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessions.remove(tab.id)?.close()
            targets.remove(tab.id)
            val status = failureOutcome(sender.eventAttempted)
            tab.diag.add("CDP $action 失败：${e.javaClass.simpleName}，" +
                if (status == Outcome.FALLBACK) "退回旧输入" else "不重复输入")
            status
        }
    }

    internal fun failureOutcome(eventAttempted: Boolean) = if (eventAttempted) Outcome.UNCERTAIN else Outcome.FALLBACK

    private class Sender(private val s: CdpSession) {
        var eventAttempted = false
            private set

        suspend fun send(method: String, params: kotlinx.serialization.json.JsonObject) {
            // 单纯移动鼠标失败时，没有发生点击或编辑，可以退回脚本。
            if (method != "Input.dispatchMouseEvent" ||
                params["type"]?.jsonPrimitive?.contentOrNull != "mouseMoved") eventAttempted = true
            s.send(method, params, timeoutMs = 2_000)
        }

        suspend fun mouse(type: String, x: Double, y: Double, button: String = "none", buttons: Int = 0) =
            send("Input.dispatchMouseEvent", buildJsonObject {
                put("type", type); put("x", x); put("y", y)
                put("button", button); put("buttons", buttons)
                put("clickCount", if (button == "left") 1 else 0); put("pointerType", "mouse")
            })

        suspend fun touch(type: String, x: Double, y: Double, end: Boolean = false) =
            send("Input.dispatchTouchEvent", buildJsonObject {
                put("type", type)
                put("touchPoints", buildJsonArray {
                    if (!end) add(buildJsonObject { put("x", x); put("y", y) })
                })
            })

        suspend fun key(spec: BrowserCdpKey, modifiers: Int = 0) {
            val params = { type: String -> buildJsonObject {
                put("type", type); put("key", spec.key); put("code", spec.code)
                put("windowsVirtualKeyCode", spec.codePoint)
                put("nativeVirtualKeyCode", spec.codePoint)
                if (modifiers != 0) put("modifiers", modifiers)
                if (spec.text != null && type == "keyDown") { put("text", spec.text); put("unmodifiedText", spec.text) }
            } }
            send("Input.dispatchKeyEvent", params(if (spec.text == null) "rawKeyDown" else "keyDown"))
            send("Input.dispatchKeyEvent", params("keyUp"))
        }

        suspend fun selectAll() {
            val ctrl = BrowserCdpKey("Control", "ControlLeft", 17)
            val a = BrowserCdpKey("a", "KeyA", 65)
            send("Input.dispatchKeyEvent", buildJsonObject {
                put("type", "rawKeyDown"); put("key", ctrl.key); put("code", ctrl.code)
                put("windowsVirtualKeyCode", ctrl.codePoint); put("modifiers", 2)
            })
            try { key(a, modifiers = 2) } finally {
                runCatching {
                    send("Input.dispatchKeyEvent", buildJsonObject {
                        put("type", "keyUp"); put("key", ctrl.key); put("code", ctrl.code)
                        put("windowsVirtualKeyCode", ctrl.codePoint)
                    })
                }
            }
        }
    }

    suspend fun act(tab: JackBrowser.Tab, x: Double, y: Double, op: String, arg: String, clear: Boolean): Outcome {
        if (op == "key" && BrowserCdpKeys.named(arg) == null) return Outcome.FALLBACK
        return perform(tab, op) {
            mouse("mouseMoved", x, y)
            if (op != "hover") {
                mouse("mousePressed", x, y, "left", 1)
                mouse("mouseReleased", x, y, "left", 0)
            }
            when (op) {
                "click" -> Unit
                "hover" -> Unit
                "type" -> {
                    key(BrowserCdpKeys.named("end")!!)
                    if (clear) { selectAll(); key(BrowserCdpKeys.named("backspace")!!) }
                    send("Input.insertText", buildJsonObject { put("text", arg) })
                }
                "delete" -> {
                    key(BrowserCdpKeys.named("end")!!)
                    val n = arg.toIntOrNull() ?: 0
                    if (n <= 0) { selectAll(); key(BrowserCdpKeys.named("backspace")!!) }
                    else repeat(n.coerceAtMost(1_000)) { key(BrowserCdpKeys.named("backspace")!!) }
                }
                "key" -> key(BrowserCdpKeys.named(arg)!!)
            }
        }
    }

    suspend fun at(tab: JackBrowser.Tab, x: Double, y: Double, x2: Double, y2: Double, durationMs: Long): Outcome =
        perform(tab, if (x == x2 && y == y2) "tap" else "swipe") {
            touch("touchStart", x, y)
            if (x != x2 || y != y2) {
                val steps = 10
                repeat(steps) { i ->
                    delay((durationMs / steps).coerceAtLeast(1))
                    val f = (i + 1).toDouble() / steps
                    touch("touchMove", x + (x2 - x) * f, y + (y2 - y) * f)
                }
            }
            touch("touchEnd", x2, y2, end = true)
        }
}

internal data class BrowserCdpKey(val key: String, val code: String, val codePoint: Int, val text: String? = null)

internal object BrowserCdpKeys {
    fun named(name: String): BrowserCdpKey? = when (name.lowercase()) {
        "enter" -> BrowserCdpKey("Enter", "Enter", 13, "\r")
        "backspace" -> BrowserCdpKey("Backspace", "Backspace", 8)
        "tab" -> BrowserCdpKey("Tab", "Tab", 9)
        "escape" -> BrowserCdpKey("Escape", "Escape", 27)
        "down" -> BrowserCdpKey("ArrowDown", "ArrowDown", 40)
        "up" -> BrowserCdpKey("ArrowUp", "ArrowUp", 38)
        "end" -> BrowserCdpKey("End", "End", 35)
        else -> null
    }
}

/** 截图像素到 CDP 的 CSS 像素，先撤销 720px 截图缩放，再按 WebView 宽度换算。 */
internal object BrowserCdpCoords {
    fun fromScreenshot(pixel: Float, viewWidth: Int, cssWidth: Float): Double {
        val imageScale = minOf(1f, 720f / viewWidth.coerceAtLeast(1))
        return pixel / imageScale * cssWidth / viewWidth.coerceAtLeast(1)
    }

    fun center(x: Float, y: Float, w: Float, h: Float): Pair<Double, Double> =
        (x + w / 2).toDouble() to (y + h / 2).toDouble()
}
