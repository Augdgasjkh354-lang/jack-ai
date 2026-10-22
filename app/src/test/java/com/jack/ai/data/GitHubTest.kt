package com.jack.ai.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** 本机起一个假的 GitHub（api 在 127.0.0.1，下载跳到 localhost 当作 codeload），走一遍搜、看、读、下载。 */
class GitHubTest {
    @get:Rule val tmp = TemporaryFolder()

    private val TOKEN = "github_pat_test"
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val port get() = server.localPort
    /** 每个请求：方法 路径 是否带了令牌 */
    private val log = mutableListOf<String>()
    private var rateLimited = false

    private val zip: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String?) {
                z.putNextEntry(ZipEntry(name)); if (text != null) z.write(text.toByteArray()); z.closeEntry()
            }
            put("me-jack-ai-abc123/", null)
            put("me-jack-ai-abc123/README.md", "# Jack AI\n安卓上的 AI 助手\n")
            put("me-jack-ai-abc123/app/", null)
            put("me-jack-ai-abc123/app/Main.kt", "fun main() {\n    println(\"hello\")\n    // TODO: 处理网络错误\n}\n")
            put("me-jack-ai-abc123/node_modules/x.js", "// TODO: 依赖里的不该搜到\n")
        }
        out.toByteArray()
    }

    @Before
    fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { serve(it) }
            }
        }
        GitHub.api = "http://127.0.0.1:$port"
    }

    @After
    fun stop() { server.close(); GitHub.api = "https://api.github.com" }

    private fun serve(sock: Socket) {
        val input = sock.getInputStream().buffered()
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
            return b.toString("UTF-8")
        }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim() }
        val authed = headers["authorization"] == "Bearer $TOKEN"
        synchronized(log) { log += "$method $target ${if (authed) "token" else "-"} ${headers["host"]}" }
        val path = target.substringBefore('?')
        val accept = headers["accept"].orEmpty()
        var extra = ""
        val (code, type, body: ByteArray) = when {
            rateLimited -> { extra = "X-RateLimit-Remaining: 0\r\n"; Triple(403, "application/json", """{"message":"API rate limit exceeded"}""".toByteArray()) }
            path == "/user" -> if (authed) Triple(200, "application/json", """{"login":"jack","name":"Jack"}""".toByteArray())
                else Triple(401, "application/json", """{"message":"Requires authentication"}""".toByteArray())
            path == "/user/repos" -> Triple(200, "application/json", """[{"full_name":"me/jack-ai","private":true,"language":"Kotlin","stargazers_count":3,"pushed_at":"2026-10-22T08:00:00Z","description":"安卓 AI 助手"}]""".toByteArray())
            path == "/search/repositories" -> Triple(200, "application/json", """{"total_count":2,"items":[{"full_name":"a/b","language":"Go","stargazers_count":1200,"pushed_at":"2026-01-02T00:00:00Z","description":"x"},{"full_name":"c/d","stargazers_count":5,"pushed_at":"2025-01-02T00:00:00Z"}]}""".toByteArray())
            path == "/repos/me/jack-ai" && authed -> Triple(200, "application/json", """{"full_name":"me/jack-ai","private":true,"default_branch":"main","size":120,"stargazers_count":3,"forks_count":0,"html_url":"https://github.com/me/jack-ai","pushed_at":"2026-10-22T08:00:00Z"}""".toByteArray())
            path.startsWith("/repos/me/jack-ai") && !authed -> Triple(404, "application/json", """{"message":"Not Found"}""".toByteArray())
            path == "/repos/me/jack-ai/contents" -> Triple(200, "application/json", """[{"name":"README.md","type":"file","size":30},{"name":"app","type":"dir","size":0}]""".toByteArray())
            path == "/repos/me/jack-ai/readme" -> Triple(200, "text/plain", "# Jack AI\n安卓上的 AI 助手\n".toByteArray())
            path == "/repos/me/jack-ai/releases/latest" -> Triple(200, "application/json", """{"tag_name":"v1.0.65","name":"","published_at":"2026-10-22T14:44:50Z","assets":[{"id":7,"name":"JackAI-v1.0.65.apk","size":11}]}""".toByteArray())
            path == "/repos/me/jack-ai/git/trees/main" -> Triple(200, "application/json", """{"tree":[{"path":"README.md","type":"blob","size":30},{"path":"app","type":"tree"},{"path":"app/Main.kt","type":"blob","size":70}],"truncated":false}""".toByteArray())
            path == "/repos/me/jack-ai/contents/app/Main.kt" && "raw" in accept -> Triple(200, "text/plain", (1..1000).joinToString("\n") { "line $it" }.toByteArray())
            // 下载：api 跳到另一个主机（localhost），看令牌有没有被带过去
            path == "/repos/me/jack-ai/zipball" -> { extra = "Location: http://localhost:$port/codeload/me/jack-ai.zip\r\n"; Triple(302, "text/plain", ByteArray(0)) }
            path == "/codeload/me/jack-ai.zip" -> Triple(200, "application/zip", zip)
            path == "/repos/me/jack-ai/releases/assets/7" -> { extra = "Location: http://localhost:$port/objects/7\r\n"; Triple(302, "text/plain", ByteArray(0)) }
            path == "/objects/7" -> Triple(200, "application/octet-stream", "APK-CONTENT".toByteArray())
            else -> Triple(404, "application/json", """{"message":"Not Found"}""".toByteArray())
        }
        val out = sock.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    @Test
    fun repoNames() {
        assertEquals("me/jack-ai", GitHub.repoOf("https://github.com/me/jack-ai/tree/main/app"))
        assertEquals("me/jack-ai", GitHub.repoOf("git@github.com:me/jack-ai.git"))
        assertEquals("me/jack-ai", GitHub.repoOf(" me/jack-ai "))
        assertNull(GitHub.repoOf("jack-ai"))
    }

    @Test
    fun searchAndMine() {
        val s = GitHub.searchRepos("", "markdown editor", "stars")
        assertTrue(s, s.contains("a/b（Go，★1200") && s.contains("搜到 2 个"))
        assertTrue(log.any { it.contains("sort=stars") })
        val m = GitHub.mine(TOKEN)
        assertTrue(m, m.contains("me/jack-ai（私有，Kotlin"))
        assertTrue(runCatching { GitHub.mine("") }.exceptionOrNull()!!.message!!.contains("填令牌"))
        assertTrue(runCatching { GitHub.searchCode("", "x") }.exceptionOrNull()!!.message!!.contains("填令牌"))
        assertEquals("jack（Jack）", GitHub.me(TOKEN))
        assertTrue(runCatching { GitHub.me("bad") }.exceptionOrNull()!!.message!!.contains("令牌不对"))
    }

    @Test
    fun privateRepoNeedsToken() {
        val e = runCatching { GitHub.overview("", "me/jack-ai", null) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("私有项目") && e!!.message!!.contains("令牌"))
    }

    @Test
    fun rateLimitIsExplained() {
        rateLimited = true
        val e = runCatching { GitHub.searchRepos("", "x") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("每小时只能 60 次"))
    }

    @Test
    fun overviewTreeAndRead() {
        val o = GitHub.overview(TOKEN, "me/jack-ai", null)
        assertTrue(o, o.contains("# me/jack-ai") && o.contains("默认分支 main") && o.contains("📁 app/") && o.contains("最新版本：v1.0.65") && o.contains("安卓上的 AI 助手"))
        val t = GitHub.tree(TOKEN, "me/jack-ai", "", "")
        assertTrue(t, t.contains("me/jack-ai@main：2 个文件") && t.contains("  Main.kt"))
        val r = GitHub.read(TOKEN, "me/jack-ai", "app/Main.kt", "", 0, 0)
        assertTrue(r, r.contains("共 1000 行，这里是第 1–800 行") && r.contains("800\tline 800") && r.contains("用 start=801 接着读"))
        val r2 = GitHub.read(TOKEN, "me/jack-ai", "app/Main.kt", "", 995, 0)
        assertTrue(r2, r2.contains("995\tline 995") && r2.contains("1000\tline 1000"))
    }

    @Test
    fun downloadRepoUnzipsAndDoesNotLeakTokenAcrossHosts() {
        val ws = tmp.newFolder("ws")
        val d = GitHub.downloadRepo(TOKEN, "me/jack-ai", "", ws)
        assertEquals(3, d.files)
        assertEquals(File(ws, ".repos/me__jack-ai"), d.dir)
        assertTrue(File(d.dir, "app/Main.kt").readText().contains("println"))
        // 最外层「作者-项目-提交号」那层去掉了；压缩包删了；本地副本认得出来
        assertFalse(File(ws, ".repos/.me__jack-ai.zip").exists())
        assertEquals(d.dir, GitHub.local(ws, "me/jack-ai"))
        // api 那一跳带了令牌，跳到别的主机后没带
        assertTrue(log.toString(), log.any { it.startsWith("GET /repos/me/jack-ai/zipball token") })
        assertTrue(log.toString(), log.any { it.startsWith("GET /codeload/me/jack-ai.zip -") })
        // 本地的目录、读文件、grep（依赖目录不搜）
        val tree = GitHub.localTree(d.dir, "")
        assertTrue(tree, tree.contains("Main.kt") && !tree.contains("x.js"))
        assertTrue(GitHub.localRead(d.dir, "app/Main.kt", 2, 2).contains("2\t    println(\"hello\")"))
        val g = GitHub.grep(d.dir, "todo", regex = false)
        assertTrue(g, g.contains("app/Main.kt:3:") && !g.contains("node_modules"))
        assertTrue(GitHub.grep(d.dir, "print\\w+\\(", regex = true).contains("app/Main.kt:2:"))
        assertTrue(runCatching { GitHub.localRead(d.dir, "../../etc/passwd", 0, 0) }.exceptionOrNull()!!.message!!.contains("路径不对"))
        // 再下一次替换旧的
        GitHub.downloadRepo(TOKEN, "me/jack-ai", "", ws)
        assertEquals(3, d.dir.walkTopDown().count { it.isFile && !it.name.startsWith(".jack") })
    }

    @Test
    fun downloadStopsAtTheLimit() {
        val ws = tmp.newFolder("ws2")
        val e = runCatching { GitHub.downloadRepo(TOKEN, "me/jack-ai", "", ws, limit = 100) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("上限"))
        assertNull(GitHub.local(ws, "me/jack-ai"))
    }

    @Test
    fun releaseAsset() {
        val ws = tmp.newFolder("ws3")
        val a = GitHub.findAsset(TOKEN, "me/jack-ai", "", "apk")
        assertEquals("JackAI-v1.0.65.apk", a.name)
        val f = GitHub.downloadAsset(TOKEN, "me/jack-ai", a, ws)
        assertEquals("APK-CONTENT", f.readText())
        assertTrue(log.toString(), log.any { it.startsWith("GET /objects/7 -") })
        assertTrue(runCatching { GitHub.findAsset(TOKEN, "me/jack-ai", "", "dmg") }.exceptionOrNull()!!.message!!.contains("JackAI-v1.0.65.apk"))
    }

    @Test
    fun zipSlipIsRefused() {
        val bad = tmp.newFile("bad.zip")
        ZipOutputStream(bad.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("root/../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dest = tmp.newFolder("out")
        val e = runCatching { GitHub.unzip(bad, dest, 1_000_000) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("不安全的路径"))
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }
}
