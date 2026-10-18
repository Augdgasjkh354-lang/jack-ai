package com.jack.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTextTest {
    @Test
    fun errorsKeepTheirReason() {
        assertTrue(ToolSession.errorText(java.net.SocketTimeoutException("x")).contains("超时"))
        assertTrue(ToolSession.errorText(java.net.UnknownHostException("imap.163.com")).contains("imap.163.com"))
        assertTrue(ToolSession.errorText(java.io.FileNotFoundException("a.txt")).contains("a.txt"))
        assertTrue(ToolSession.errorText(java.io.IOException("断了")).contains("断了"))
        assertTrue(ToolSession.errorText(IllegalStateException()).contains("IllegalStateException"))
    }
}
