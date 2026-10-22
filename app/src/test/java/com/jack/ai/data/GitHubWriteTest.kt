package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** 假 GitHub，记着分支指向哪个提交、收到了哪些写请求，走一遍：下载 → 本地改 → diff → 推送；冲突、新分支、PR、issue、打包日志。 */
class GitHubWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val TOKEN = "github_pat_test"
    private val R = "me/app"
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** 分支 → 提交；提交 → 树里每个文件 (路径 → blob sha, mode)。 */
    private val refs = mutableMapOf("main" to "c1")
    private val trees = mutableMapOf(
        "c1" to mapOf(
            "README.md" to (sha("# App\n") to "100644"),
            "gradlew" to (sha("#!/bin/sh\necho hi\n") to "100755"),
            "src/Main.kt" to (sha("fun main() {\n    println(\"hi\")\n}\n") to "100644"),
            "old.txt" to (sha("bye\n") to "100644"),
        ),
    )
    private val writes = mutableListOf<Pair<String, JsonObject>>()
    private var blobN = 0
    private var commitN = 0

    private fun sha(s: String) = GitHubWrite.blobSha(s.toByteArray())

    private val zip: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry("me-app-c1/$name")); z.write(text.toByteArray()); z.closeEntry() }
            put("README.md", "# App\n"); put("gradlew", "#!/bin/sh\necho hi\n")
            put("src/Main.kt", "fun main() {\n    println(\"hi\")\n}\n"); put("old.txt", "bye\n")
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
        GitHub.api = "http://127.0.0.1:${server.localPort}"
    }

    @After
    fun stop() { server.close(); GitHub.api = "https://api.github.com" }

    private fun serve(sock: Socket) {
        val input = sock.getInputStream().buffered()
        fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim() }
        val len = headers["content-length"]?.toInt() ?: 0
        val bodyText = ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }.decodeToString()
        val body = if (bodyText.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(bodyText).jsonObject
        val path = target.substringBefore('?').removePrefix("/repos/$R")
        if (method != "GET") synchronized(writes) { writes += "$method $path" to body }
        var extra = ""
        val (code, out: Any) = when {
            headers["authorization"] != "Bearer $TOKEN" && method != "GET" -> 401 to """{"message":"Bad credentials"}"""
            method == "GET" && path == "" -> 200 to """{"full_name":"$R","default_branch":"main"}"""
            method == "GET" && path.startsWith("/commits/") -> 200 to """{"sha":"${refs[path.removePrefix("/commits/")]}"}"""
            method == "GET" && path.startsWith("/zipball/") -> 200 to zip
            method == "GET" && path.startsWith("/git/ref/heads/") -> refs[path.removePrefix("/git/ref/heads/")]
                ?.let { 200 to """{"object":{"sha":"$it"}}""" } ?: (404 to """{"message":"Not Found"}""")
            method == "GET" && path.startsWith("/git/commits/") -> 200 to """{"tree":{"sha":"t-${path.removePrefix("/git/commits/")}"}}"""
            method == "GET" && path.startsWith("/git/trees/t-") -> {
                val c = path.removePrefix("/git/trees/t-")
                200 to """{"tree":[${trees[c]!!.entries.joinToString(",") { (p, v) -> """{"path":"$p","type":"blob","sha":"${v.first}","mode":"${v.second}"}""" }}]}"""
            }
            method == "POST" && path == "/git/blobs" -> 201 to """{"sha":"blob${++blobN}"}"""
            method == "POST" && path == "/git/trees" -> 201 to """{"sha":"newtree"}"""
            method == "POST" && path == "/git/commits" -> 201 to """{"sha":"n${++commitN}"}"""
            method == "PATCH" && path.startsWith("/git/refs/heads/") -> { refs[path.removePrefix("/git/refs/heads/")] = body["sha"]!!.jsonPrimitive.content; 200 to "{}" }
            method == "POST" && path == "/git/refs" -> { refs[body["ref"]!!.jsonPrimitive.content.removePrefix("refs/heads/")] = body["sha"]!!.jsonPrimitive.content; 201 to "{}" }
            method == "POST" && path == "/pulls" -> 201 to """{"number":12,"html_url":"https://github.com/$R/pull/12"}"""
            method == "PUT" && path == "/pulls/12/merge" -> 200 to """{"merged":true,"sha":"abcdef123"}"""
            method == "POST" && path == "/issues/3/comments" -> 201 to """{"html_url":"https://github.com/$R/issues/3#c1"}"""
            method == "GET" && path == "/actions/runs" -> 200 to """{"workflow_runs":[{"id":99,"name":"打包 APK","run_number":67,"status":"completed","conclusion":"failure","head_branch":"main","head_sha":"n1abcdef","display_title":"fix","created_at":"2026-10-22T16:11:26Z"}]}"""
            method == "GET" && path == "/actions/runs/99/jobs" -> 200 to """{"jobs":[{"id":5,"name":"build","status":"completed","conclusion":"failure","steps":[{"name":"Build APK","conclusion":"failure"}]}]}"""
            method == "GET" && path == "/actions/jobs/5/logs" -> { extra = "Location: http://localhost:${server.localPort}/logs/5\r\n"; 302 to "" }
            method == "GET" && target == "/logs/5" -> 200 to "2026-10-22T16:12:00.1Z > Task :app:compileReleaseKotlin\n2026-10-22T16:12:01.2Z e: Main.kt:3:5 Unresolved reference 'printn'.\n"
            method == "POST" && path == "/actions/runs/99/rerun-failed-jobs" -> 201 to ""
            else -> 404 to """{"message":"Not Found"}"""
        }
        val bytes = if (out is ByteArray) out else (out as String).toByteArray()
        val o = sock.getOutputStream()
        o.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        o.write(bytes); o.flush()
    }

    private fun download(): Pair<File, File> {
        val ws = tmp.newFolder()
        val d = GitHub.downloadRepo(TOKEN, R, "", ws)
        return d.dir to GitHub.baseDir(ws, R)
    }

    @Test
    fun gitBlobHashMatchesGit() {
        // git hash-object 对 "hello\n" 给的就是这个
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubWrite.blobSha("hello\n".toByteArray()))
    }

    @Test
    fun hunkShowsOnlyTheChangedMiddle() {
        val h = GitHubWrite.hunk("a\nb\nc\nd\n", "a\nB\nc\nd\n")
        assertEquals("@@ 第 2 行起\n- b\n+ B\n", h)
    }

    @Test
    fun editDiffAndPushToMain() {
        val (local, base) = download()
        assertEquals(GitHub.Meta(R, "main", "c1"), GitHub.meta(local))
        GitHubWrite.edit(local, "src/Main.kt", "println(\"hi\")", "println(\"hello\")", false)
        GitHubWrite.write(local, "docs/NOTES.md", "# 笔记\n")
        GitHubWrite.remove(local, "old.txt")
        val diff = GitHubWrite.diff(local, base)
        assertTrue(diff, diff.contains("3 个文件有改动") && diff.contains("+ 新增 docs/NOTES.md") && diff.contains("- 删除 old.txt") &&
            diff.contains("-     println(\"hi\")") && diff.contains("+     println(\"hello\")"))

        val p = GitHubWrite.commit(TOKEN, local, base, "改问候语", "")
        assertEquals("main", p.branch); assertEquals(3, p.files)
        val tree = writes.first { it.first == "POST /git/trees" }.second
        assertEquals("t-c1", tree["base_tree"]!!.jsonPrimitive.content)
        val entries = tree["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it["path"]!!.jsonPrimitive.content }
        assertEquals(JsonNull, entries["old.txt"]!!["sha"])
        assertEquals("100644", entries["src/Main.kt"]!!["mode"]!!.jsonPrimitive.content)
        val commit = writes.first { it.first == "POST /git/commits" }.second
        assertEquals("c1", commit["parents"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("改问候语", commit["message"]!!.jsonPrimitive.content)
        // 不强制推送
        val ref = writes.first { it.first == "PATCH /git/refs/heads/main" }.second
        assertEquals(false, ref["force"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(p.sha, refs["main"])
        // 推完：原样副本跟上，没有剩下的改动；记录的提交更新了
        assertTrue(GitHubWrite.changes(local, base).isEmpty())
        assertEquals(p.sha, GitHub.meta(local)!!.commit)
        // 文件内容按 base64 发过去
        val blob = writes.first { it.first == "POST /git/blobs" }.second
        assertEquals("base64", blob["encoding"]!!.jsonPrimitive.content)
    }

    @Test
    fun executableBitIsKept() {
        val (local, base) = download()
        GitHubWrite.edit(local, "gradlew", "echo hi", "echo hello", false)
        GitHubWrite.commit(TOKEN, local, base, "改脚本", "")
        val e = writes.first { it.first == "POST /git/trees" }.second["tree"]!!.jsonArray[0].jsonObject
        assertEquals("100755", e["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusesToOverwriteSomeoneElsesChangeButKeepsUnrelatedOnes() {
        val (local, base) = download()
        // 下载之后别人推了 c2：改了 src/Main.kt
        trees["c2"] = trees["c1"]!! + ("src/Main.kt" to (sha("fun main() {}\n") to "100644"))
        refs["main"] = "c2"
        GitHubWrite.edit(local, "src/Main.kt", "hi", "yo", false)
        val e = runCatching { GitHubWrite.commit(TOKEN, local, base, "x", "") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("src/Main.kt") && e!!.message!!.contains("新分支"))
        assertFalse(writes.any { it.first.startsWith("PATCH") })
        // 只改别的文件：可以推，以 c2 为父提交，c2 的改动保留
        File(local, "src/Main.kt").writeText(File(base, "src/Main.kt").readText())
        GitHubWrite.edit(local, "README.md", "# App", "# App 2", false)
        GitHubWrite.commit(TOKEN, local, base, "改说明", "")
        val commit = writes.first { it.first == "POST /git/commits" }.second
        assertEquals("c2", commit["parents"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("t-c2", writes.first { it.first == "POST /git/trees" }.second["base_tree"]!!.jsonPrimitive.content)
    }

    @Test
    fun pushToNewBranchThenPrAndMerge() {
        val (local, base) = download()
        GitHubWrite.edit(local, "README.md", "# App", "# App 2", false)
        val p = GitHubWrite.commit(TOKEN, local, base, "改说明", "jack/readme")
        assertEquals("jack/readme", p.branch)
        val ref = writes.first { it.first == "POST /git/refs" }.second
        assertEquals("refs/heads/jack/readme", ref["ref"]!!.jsonPrimitive.content)
        assertEquals("c1", writes.first { it.first == "POST /git/commits" }.second["parents"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("c1", refs["main"]) // main 没动
        assertTrue(GitHubWrite.createPr(TOKEN, R, "改说明", "jack/readme", "", "说明").contains("#12"))
        assertEquals("main", writes.first { it.first == "POST /pulls" }.second["base"]!!.jsonPrimitive.content)
        assertTrue(GitHubWrite.merge(TOKEN, R, 12, "").contains("已合并"))
        assertEquals("squash", writes.first { it.first == "PUT /pulls/12/merge" }.second["merge_method"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingToCommitAndUnsafePaths() {
        val (local, base) = download()
        assertTrue(runCatching { GitHubWrite.commit(TOKEN, local, base, "x", "") }.exceptionOrNull()!!.message!!.contains("没有改动"))
        listOf("../x", "/../../etc/x", ".jack-repo", "").forEach { p ->
            assertTrue(p, runCatching { GitHubWrite.write(local, p, "x") }.exceptionOrNull()!!.message!!.contains("路径不对"))
        }
        assertTrue(runCatching { GitHub.send("", "POST", "/x", null) }.exceptionOrNull()!!.message!!.contains("写权限的令牌"))
    }

    @Test
    fun issuesAndActions() {
        assertTrue(GitHubWrite.comment(TOKEN, R, 3, "看过了").contains("#3"))
        assertEquals("看过了", writes.first { it.first == "POST /issues/3/comments" }.second["body"]!!.jsonPrimitive.content)
        val runs = GitHubWrite.runs(TOKEN, R)
        assertTrue(runs, runs.contains("运行 99｜打包 APK #67｜✗ 失败｜main n1abcde"))
        val run = GitHubWrite.run(TOKEN, R, 99)
        assertTrue(run, run.contains("失败的步骤：Build APK") && run.contains("e: Main.kt:3:5 Unresolved reference 'printn'.") && !run.contains("16:12:01.2Z"))
        assertTrue(GitHubWrite.rerun(TOKEN, R, 99).contains("重跑"))
    }
}
