package com.jack.ai.data

import com.jack.ai.data.phone.LinuxSandbox
import com.jack.ai.data.phone.TerminalHub
import com.jack.ai.data.phone.TerminalRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 用户终端：伪终端参数、输出缓冲、Kavi 跑的命令记录。 */
class TerminalTest {
    @Test fun ptyArgsGoBeforeTheCommand() {
        val a = LinuxSandbox.argv("m", "p", "r", "w", emptyList(), 0, true, "", emptyList(),
            pty = 100 to 30, sizeFile = "/tmp/s", shell = listOf("/bin/sh", "-l"))
        val dash = a.indexOf("--")
        assertTrue(a.subList(0, dash).containsAll(listOf("--pty", "--pty-cols", "100", "--pty-rows", "30", "--pty-size", "/tmp/s")))
        assertEquals(listOf("/bin/sh", "-l"), a.takeLast(2))
        // 不限时：没有 --timeout
        assertTrue("--timeout" !in a)
        val env = LinuxSandbox.env("l", "t", "UTC", pty = true)
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("/dev/null", env["HISTFILE"])
        assertTrue("TERM" !in LinuxSandbox.env("l", "t", "UTC"))
    }

    @Test fun ringKeepsTheNewest() {
        val r = TerminalRingBuffer(8)
        r.append("abcdef".toByteArray())
        r.append("ghij".toByteArray())
        assertEquals("cdefghij", String(r.snapshot()))
        r.append("0123456789".toByteArray())
        assertEquals("23456789", String(r.snapshot()))
    }

    @Test fun kaviCommandsAreLoggedReadOnly() {
        val ws = File("build/tmp/ws-log-test")
        TerminalHub.logKavi(ws, "算账", "python a.py", "1\n2\n", 0)
        TerminalHub.logKavi(ws, "算账", "false", "", 1)
        val s = TerminalHub.sessions.value.single { it.workspace == ws }
        assertTrue(s.readOnly)
        val text = String(s.snapshot())
        assertTrue(text.contains("python a.py\r\n1\r\n2\r\n"))
        assertTrue(text.contains("[退出码 1]"))
        TerminalHub.close(s.id)
    }
}
