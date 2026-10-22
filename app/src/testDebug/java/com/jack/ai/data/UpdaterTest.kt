package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** 假 GitHub 上发了一个比手上新的版本：查到 → 下载 → 校验 → 记下等安装；校验不对就删掉。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val apk = ByteArray(200_000) { (it % 97).toByte() }
    private var digest = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var tag = "v1.0.900"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val input = sock.getInputStream().buffered()
                    fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
                    val target = line().split(" ")[1]
                    while (line().isNotEmpty()) {}
                    val path = target.substringBefore('?').removePrefix("/repos/${Updater.REPO}")
                    val body: ByteArray = when {
                        path == "/releases/latest" -> """{"tag_name":"$tag","published_at":"2026-10-22T17:00:00Z","assets":[{"id":5,"name":"JackAI-$tag.apk","size":${apk.size},"digest":"sha256:$digest"}]}""".toByteArray()
                        path == "/releases/assets/5" -> apk
                        path.startsWith("/compare/") -> """{"commits":[{"commit":{"message":"Add GitHub sync\n\ndetails"}},{"commit":{"message":"Updater: self-update"}}]}""".toByteArray()
                        else -> """{"message":"Not Found"}""".toByteArray()
                    }
                    val code = if (body.decodeToString().startsWith("{\"message\"")) 404 else 200
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                    }
                }
            }
        }
        GitHub.api = "http://127.0.0.1:${server.localPort}"
    }

    @After
    fun stop() { server.close(); GitHub.api = "https://api.github.com" }

    @Test
    fun downloadsVerifiesAndRemembers() {
        assertEquals(69L, Updater.codeOf("v1.0.69"))
        assertNull(Updater.ready(ctx))
        val r = Updater.checkAndDownload(ctx, "") as Updater.Result.Downloaded
        assertEquals(900L, r.ready.code)
        assertTrue(r.ready.file.name == "JackAI-v1.0.900.apk" && r.ready.file.readBytes().contentEquals(apk))
        // 最新的提交在前
        assertEquals("· Updater: self-update\n· Add GitHub sync", r.ready.notes)
        assertEquals(r.ready.file, Updater.ready(ctx)!!.file)
        // 再查一次不重下；同一版本只提醒一次
        assertTrue(Updater.checkAndDownload(ctx, "") is Updater.Result.Downloaded)
        assertTrue(Updater.markNotified(ctx, 900))
        assertFalse(Updater.markNotified(ctx, 900))
        // 安装界面用的是 FileProvider 的地址
        val i = Updater.installIntent(ctx, r.ready.file)
        assertEquals("application/vnd.android.package-archive", i.type)
        assertTrue(i.data.toString(), i.data.toString().startsWith("content://"))
    }

    @Test
    fun badChecksumIsThrownAway() {
        tag = "v1.0.901"
        digest = "0".repeat(64)
        val e = runCatching { Updater.checkAndDownload(ctx, "") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("校验不对"))
        assertNull(Updater.ready(ctx))
    }

    @Test
    fun notNewerMeansUpToDate() {
        tag = "v1.0.0"
        assertTrue(Updater.checkAndDownload(ctx, "") is Updater.Result.UpToDate)
    }
}
