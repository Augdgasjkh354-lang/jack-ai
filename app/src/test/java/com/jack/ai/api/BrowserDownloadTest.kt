package com.jack.ai.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class BrowserDownloadTest {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val seen = mutableListOf<Map<String, String>>()
    private var body = ByteArray(300_000) { (it % 251).toByte() }
    private var dropFirst = false

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val input = sock.getInputStream().buffered()
                    val headers = mutableMapOf<String, String>()
                    fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
                    headers["_line"] = line()
                    while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim() }
                    synchronized(seen) { seen += headers }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (dropFirst) { dropFirst = false; out.write(body, 0, 1000); out.flush(); return@use }
                    out.write(body); out.flush()
                }
            }
        }
    }

    @After
    fun stop() { server.close(); Net.setConfig(Net.Config()) }

    private fun pending(path: String = "/file.zip") =
        BrowserDownload.Pending("http://127.0.0.1:${server.localPort}$path", "file.zip", "application/zip", body.size.toLong(), "TestUA", "https://example.com/page")

    @Test
    fun names() {
        assertEquals("报告 2026.pdf", BrowserDownload.nameFor("https://x.com/d?id=1", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A%202026.pdf", null))
        assertEquals("a.zip", BrowserDownload.nameFor("https://x.com/dl", "attachment; filename=\"a.zip\"", null))
        assertEquals("sing-box.tar.gz", BrowserDownload.nameFor("https://github.com/r/releases/download/v1/sing-box.tar.gz?x=1", null, null))
        assertEquals("下载.pdf", BrowserDownload.nameFor("https://x.com/get", null, "application/pdf; charset=binary"))
        // 文件名里的路径分隔符一律换掉，不会写到别的目录
        assertEquals("_etc_passwd", BrowserDownload.nameFor("https://x.com/a", "attachment; filename=\"/etc/passwd\"", null))
        assertTrue(!BrowserDownload.nameFor("https://x.com/a", "attachment; filename=\"../../x\"", null).contains("/"))
    }

    @Test
    fun fetchesWithCookieRefererAndReportsProgress() {
        val out = ByteArrayOutputStream()
        var last = 0L
        val n = BrowserDownload.fetch(pending(), "session=abc", BrowserNet.AUTO, BrowserDownload.HARD_LIMIT, open = { out }, reset = { out.reset() },
            progress = { got, _ -> last = got })
        assertEquals(body.size.toLong(), n)
        assertTrue(out.toByteArray().contentEquals(body))
        assertEquals(body.size.toLong(), last)
        val h = seen.last()
        assertEquals("session=abc", h["cookie"]); assertEquals("TestUA", h["user-agent"]); assertEquals("https://example.com/page", h["referer"])
    }

    @Test
    fun retriesOnceAfterADroppedConnection() {
        dropFirst = true
        val out = ByteArrayOutputStream()
        val n = BrowserDownload.fetch(pending(), null, BrowserNet.AUTO, BrowserDownload.HARD_LIMIT, open = { out }, reset = { out.reset() })
        assertEquals(body.size.toLong(), n)
        assertTrue(out.toByteArray().contentEquals(body))
        assertEquals(2, seen.size)
    }

    @Test
    fun limitsAndOddLinks() {
        val e = runCatching { BrowserDownload.fetch(pending(), null, BrowserNet.AUTO, 1000, open = { ByteArrayOutputStream() }, reset = {}) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("上限"))
        val b = runCatching { BrowserDownload.fetch(pending().copy(url = "blob:https://x.com/123"), null, BrowserNet.AUTO, 1000, open = { ByteArrayOutputStream() }, reset = {}) }.exceptionOrNull()
        assertTrue(b?.message.orEmpty(), b?.message.orEmpty().contains("暂时存不下来"))
    }

    @Test
    fun forcedProxyWithoutProxyFailsInsteadOfGoingDirect() {
        // 浏览器选了「全部走代理」但代理没开：下载失败，不偷偷直连（用一个外网地址，本机地址永远直连）
        Net.setConfig(Net.Config(enabled = false))
        val p = pending().copy(url = "http://example.invalid/file.zip")
        val e = runCatching { BrowserDownload.fetch(p, null, BrowserNet.PROXY, 1000, open = { ByteArrayOutputStream() }, reset = {}) }.exceptionOrNull()
        assertTrue(e.toString(), e is java.io.IOException)
        assertEquals(0, seen.size)
    }
}
