package com.jack.ai.ui

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 浏览器真实输入的事件顺序：Android 和无头浏览器测试共用这份实现。 */
internal class CdpInputScript(
    private val send: suspend (String, JsonObject) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private suspend fun mouse(type: String, x: Double, y: Double, button: String = "none", buttons: Int = 0) =
        send("Input.dispatchMouseEvent", buildJsonObject {
            put("type", type); put("x", x); put("y", y)
            put("button", button); put("buttons", buttons)
            put("clickCount", if (button == "left") 1 else 0); put("pointerType", "mouse")
        })

    private suspend fun touch(type: String, x: Double, y: Double, end: Boolean = false) =
        send("Input.dispatchTouchEvent", buildJsonObject {
            put("type", type)
            put("touchPoints", buildJsonArray {
                if (!end) add(buildJsonObject { put("x", x); put("y", y); put("id", 1) })
            })
        })

    private suspend fun key(spec: BrowserCdpKey, modifiers: Int = 0) {
        val params = { type: String -> buildJsonObject {
            put("type", type); put("key", spec.key); put("code", spec.code)
            put("windowsVirtualKeyCode", spec.codePoint)
            put("nativeVirtualKeyCode", spec.codePoint)
            if (modifiers != 0) put("modifiers", modifiers)
            if (spec.text != null && type == "keyDown") {
                put("text", spec.text); put("unmodifiedText", spec.text)
            }
        } }
        send("Input.dispatchKeyEvent", params(if (spec.text == null) "rawKeyDown" else "keyDown"))
        send("Input.dispatchKeyEvent", params("keyUp"))
    }

    private suspend fun selectAll() {
        val ctrl = BrowserCdpKey("Control", "ControlLeft", 17)
        val a = BrowserCdpKey("a", "KeyA", 65)
        send("Input.dispatchKeyEvent", buildJsonObject {
            put("type", "rawKeyDown"); put("key", ctrl.key); put("code", ctrl.code)
            put("windowsVirtualKeyCode", ctrl.codePoint); put("modifiers", 2)
        })
        try {
            key(a, modifiers = 2)
        } finally {
            runCatching {
                send("Input.dispatchKeyEvent", buildJsonObject {
                    put("type", "keyUp"); put("key", ctrl.key); put("code", ctrl.code)
                    put("windowsVirtualKeyCode", ctrl.codePoint)
                })
            }
        }
    }

    suspend fun act(x: Double, y: Double, op: String, arg: String = "", clear: Boolean = false) {
        mouse("mouseMoved", x, y)
        if (op in setOf("click", "type", "delete")) {
            mouse("mousePressed", x, y, "left", 1)
            mouse("mouseReleased", x, y, "left", 0)
        }
        when (op) {
            "click", "hover" -> Unit
            "type" -> {
                key(BrowserCdpKeys.named("end")!!)
                if (clear) {
                    selectAll()
                    key(BrowserCdpKeys.named("backspace")!!)
                }
                send("Input.insertText", buildJsonObject { put("text", arg) })
            }
            "delete" -> {
                key(BrowserCdpKeys.named("end")!!)
                val n = arg.toIntOrNull() ?: 0
                if (n <= 0) {
                    selectAll()
                    key(BrowserCdpKeys.named("backspace")!!)
                } else {
                    repeat(n.coerceAtMost(1_000)) { key(BrowserCdpKeys.named("backspace")!!) }
                }
            }
            "key" -> key(BrowserCdpKeys.named(arg) ?: error("Unknown key: $arg"))
        }
    }

    suspend fun at(x: Double, y: Double, x2: Double, y2: Double, durationMs: Long) {
        touch("touchStart", x, y)
        if (x != x2 || y != y2) {
            val steps = 10
            repeat(steps) { i ->
                pause((durationMs / steps).coerceAtLeast(1))
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
