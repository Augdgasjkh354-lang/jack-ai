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
    /** 服务器支不支持断点续传（认不认 Range）。 */
    private var ranges = false
    private var type = "application/octet-stream"

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
                    val from = if (ranges) headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0 else 0
                    val status = if (from > 0) "206 Partial Content" else "200 OK"
                    val range = if (from > 0) "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n" else ""
                    out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size - from}\r\n${range}ETag: \"v1\"\r\nConnection: close\r\n\r\n".toByteArray())
                    if (dropFirst) { dropFirst = false; out.write(body, from, 1000); out.flush(); return@use }
                    out.write(body, from, body.size - from); out.flush()
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

    /** 存进内存的去处，记下有没有被续写过。 */
    private class Mem : BrowserDownload.Sink {
        val out = ByteArrayOutputStream()
        var appends = 0
        var discarded = false
        override fun open(append: Boolean): java.io.OutputStream { if (append) appends++ else out.reset(); return out }
        override fun discard() { discarded = true }
    }

    @Test
    fun resumesFromWhereItBroke() {
        ranges = true; dropFirst = true
        val m = Mem()
        var named = ""
        val r = BrowserDownload.download(pending().copy(name = ""), null, BrowserNet.AUTO, BrowserDownload.HARD_LIMIT, { n, _ -> named = n; m })
        assertEquals(body.size.toLong(), r.bytes)
        assertTrue(m.out.toByteArray().contentEquals(body))
        assertEquals(1, m.appends)
        // 第二次只要后面的，带着 If-Range 确认文件没换
        assertEquals("bytes=1000-", seen.last()["range"]); assertEquals("\"v1\"", seen.last()["if-range"])
        assertEquals("file.zip", named)
    }

    @Test
    fun startsOverWhenTheSiteCannotResume() {
        ranges = false; dropFirst = true
        val m = Mem()
        val r = BrowserDownload.download(pending(), null, BrowserNet.AUTO, BrowserDownload.HARD_LIMIT, { _, _ -> m })
        assertEquals(body.size.toLong(), r.bytes)
        assertTrue(m.out.toByteArray().contentEquals(body))
        assertEquals(0, m.appends)
    }

    @Test
    fun pagesAreNotFiles() {
        type = "text/html; charset=utf-8"
        val m = Mem()
        val e = runCatching { BrowserDownload.download(pending(), null, BrowserNet.AUTO, BrowserDownload.HARD_LIMIT, { _, _ -> m }, rejectPage = true) }.exceptionOrNull()
        assertTrue(e.toString(), e is BrowserDownload.PageException)
        assertTrue(BrowserDownload.isPage("application/xhtml+xml"))
    }

    @Test
    fun dataUrls() {
        val (mime, bytes) = BrowserDownload.decodeData("data:text/csv;base64," + java.util.Base64.getEncoder().encodeToString("a,b\n1,2".toByteArray()))!!
        assertEquals("text/csv", mime); assertEquals("a,b\n1,2", String(bytes))
        assertEquals("你好 x", String(BrowserDownload.decodeData("data:,%E4%BD%A0%E5%A5%BD%20x")!!.second))
        val m = Mem()
        var named = ""
        BrowserDownload.download(pending().copy(url = "data:application/json;base64,e30=", name = ""), null, BrowserNet.AUTO, 1000, { n, _ -> named = n; m })
        assertEquals("下载.json", named); assertEquals("{}", m.out.toString())
        assertEquals(0, seen.size)
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
