package com.jack.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    private fun boom(msg: String): Throwable = try { listOf<Int>()[0]; error("") } catch (e: Exception) { RuntimeException("包装：$msg", IllegalStateException("密码是 sk-123 $msg", e)) }

    @Test fun reportKeepsCodePositionsButNoMessages() {
        val e = boom("用户的聊天内容")
        val text = CrashLog.stripped(e)
        assertTrue(text.contains("java.lang.IllegalStateException") && text.contains("CrashLogTest"))
        assertFalse(text.contains("聊天内容") || text.contains("sk-123"))
        assertTrue(CrashLog.title(e).startsWith("闪退："))
    }

    @Test fun sameCrashSameSignature() {
        assertEquals(CrashLog.signature(boom("a")), CrashLog.signature(boom("b")))
    }
}
