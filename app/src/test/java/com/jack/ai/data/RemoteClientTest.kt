package com.jack.ai.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 和真的站点（remote-site/，本地用 `npx wrangler dev` 跑起来）走一遍：网页发消息带文件 → Kavi 取到、下载文件 → 问确认、网页回答 →
 * Kavi 写回答、传文件 → 网页看到。设了 REMOTE_SITE_URL 和 REMOTE_SITE_KEY 才测。
 */
class RemoteClientTest {
    private val base = System.getenv("REMOTE_SITE_URL").orEmpty()
    private val key = System.getenv("REMOTE_SITE_KEY").orEmpty()

    @Test
    fun roundTrip() {
        assumeTrue("没设 REMOTE_SITE_URL，跳过", base.isNotEmpty())
        val http = OkHttpClient()
        val kavi = RemoteClient(base, key, http)
        assertTrue(kavi.ping() >= 1)
        try { RemoteClient(base, "wrong", http).ping(); error("错的密钥不该连上") } catch (e: IOException) { assertTrue(e.message!!.contains("密钥")) }

        // 网页那边：登录、传文件、发消息
        val jsonType = "application/json".toMediaType()
        val login = http.newCall(Request.Builder().url("$base/api/login").post("""{"key":"$key"}""".toRequestBody(jsonType)).build()).execute()
        val cookie = login.header("set-cookie")!!.substringBefore(';'); login.close()
        fun web(path: String, body: String? = null, raw: ByteArray? = null) = http.newCall(Request.Builder().url("$base$path").header("cookie", cookie)
            .apply { if (body != null) post(body.toRequestBody(jsonType)) else if (raw != null) post(raw.toRequestBody("text/plain".toMediaType())) }.build()).execute().use { it.body!!.string() }
        while (kavi.poll().messages.isNotEmpty()) Unit
        val up = web("/api/upload?name=note.txt&type=text/plain", raw = "文件内容".toByteArray())
        web("/api/send", """{"text":"远程你好","files":[$up]}""")

        val p = kavi.poll()
        val m = p.messages.single()
        assertEquals("远程你好", m.text)
        val got = kavi.download(m.files.single(), File.createTempFile("remote", ".txt"))
        assertEquals("文件内容", got.readText())

        val ask = kavi.ask(m.id, "发邮件？", "给张三", "发送")
        val state = web("/api/state?conv=${m.conv}")
        assertTrue(state, state.contains("发邮件？"))
        web("/api/confirm", """{"id":"$ask","ok":true}""")
        assertEquals(mapOf(ask to true), kavi.poll().answers)

        val f = File.createTempFile("report", ".md").apply { writeText("# 报告") }
        val rf = kavi.upload(f, "text/markdown")
        kavi.reply(m.id, "答完了", listOf("查资料"), listOf(rf), "done")
        val after = web("/api/state?conv=${m.conv}")
        assertTrue(after, after.contains("答完了") && after.contains(rf.key) && after.contains("查资料"))
    }
}
