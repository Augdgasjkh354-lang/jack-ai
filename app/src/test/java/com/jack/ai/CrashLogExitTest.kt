package com.jack.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统记下的卡死、底层崩溃：只捞出能定位的那几行。 */
class CrashLogExitTest {
    @Test fun anrKeepsOnlyTheMainThreadStack() {
        val trace = """
            ----- pid 123 at 2026-11-05 -----
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              at com.jack.ai.ui.JackBrowser.read(JackBrowser.kt:1700)
              - waiting to lock <0x1> held by thread 22
              at android.os.Handler.dispatchMessage(Handler.java:106)

            "worker" prio=5 tid=22 Runnable
              at com.jack.ai.other.Thing.run(Thing.kt:1)
        """.trimIndent()
        val frames = CrashLog.anrMainThread(trace)
        assertTrue(frames.first().startsWith("\"main\""))
        assertTrue(frames.any { "JackBrowser.read" in it })
        assertTrue(frames.none { "Thing.run" in it })
    }

    @Test fun nativeTombstoneUsesOnlyCrashedThread() {
        val signal = field(1, 5) + field(2, "SIGTRAP") + field(4, "TRAP_BRKPT")
        val idle = field(1, 42) + field(2, "webview-worker") +
            field(4, field(1, 20) + field(6, "libwebviewchromium.so"))
        val crashed = field(1, 73) + field(2, "onnx-worker") +
            field(4, field(1, 4096) + field(6, "/data/app/libonnxruntime.so") + field(4, "OrtRun"))
        fun thread(id: Long, data: ByteArray) = field(16, field(1, id) + field(2, data))
        val tombstone = field(6, 73) + field(10, signal) + field(14, "sk-private-token") +
            thread(42, idle) + thread(73, crashed)
        val frames = CrashLog.nativeFrames(tombstone)

        assertEquals("SIGTRAP (TRAP_BRKPT)", frames[0])
        assertEquals("崩溃线程 tid=73 (onnx-worker)", frames[1])
        assertTrue(frames[2].startsWith("#00 pc 00001000 libonnxruntime.so (OrtRun)"))
        assertTrue(frames.none { "webview" in it || "sk-private-token" in it })
    }

    @Test fun malformedNativeTombstoneDoesNotInventStack() {
        val bytes = byteArrayOf(0x82.toByte(), 0x80.toByte()) + "libonnxruntime.so".toByteArray()
        assertTrue(CrashLog.nativeFrames(bytes).isEmpty())
    }

    private fun varint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var n = value
        while (n >= 128) {
            out.write((n.toInt() and 127) or 128)
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }

    private fun field(id: Int, value: Long): ByteArray = varint(id.toLong() shl 3) + varint(value)
    private fun field(id: Int, value: Int): ByteArray = field(id, value.toLong())
    private fun field(id: Int, value: String): ByteArray = field(id, value.toByteArray())
    private fun field(id: Int, value: ByteArray): ByteArray =
        varint((id.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
}
