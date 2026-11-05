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

    @Test fun nativeTombstoneYieldsReadableLibraryNames() {
        val bytes = byteArrayOf(1, 2) + "SIGSEGV".toByteArray() + byteArrayOf(0, 9) +
            "/system/app/WebViewGoogle/libwebviewchromium.so".toByteArray() + byteArrayOf(0) + "hello".toByteArray()
        assertEquals(listOf("SIGSEGV", "/system/app/WebViewGoogle/libwebviewchromium.so"), CrashLog.nativeFrames(bytes))
    }
}
