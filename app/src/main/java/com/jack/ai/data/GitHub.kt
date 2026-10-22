package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * GitHub：搜项目、看自己的项目（填了令牌连私有的也能看）、读目录和文件、下载整个项目或 Releases 里的文件。
 * 这里是读的部分；改代码、推送、PR、issue、自动打包在 GitHubWrite。网络跟着 Jack 专用代理的分流走（GitHub 的网址都不在直连名单里）。
 * 下载下来的项目放在对话文件夹的 .repos/作者__项目名/ 里，之后读目录、读文件、全文搜索都用本地的，又快又不占次数。
 */
object GitHub {
    internal var api = "https://api.github.com"

    class GitHubException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    /** 普通接口：60 秒读超时（搜代码有时慢）。 */
    private val http: OkHttpClient by lazy { com.jack.ai.api.sharedHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }
    /** 下载：读超时放宽，整体不限时（大文件走代理可能要几分钟）。 */
    private val download: OkHttpClient by lazy {
        com.jack.ai.api.sharedHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    /** 超过这个大小先问用户；超过 HARD_LIMIT 不下。 */
    const val ASK_ABOVE = 500L * 1024 * 1024
    const val HARD_LIMIT = 2L * 1024 * 1024 * 1024

    // ---------- 请求 ----------

    private fun request(token: String, path: String, accept: String = "application/vnd.github+json"): Request =
        Request.Builder().url(if (path.startsWith("http")) path else api + path)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "JackAI")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .build()

    /** 调接口拿 JSON。出错时把 GitHub 的说法翻成能照着做的中文。 */
    fun get(token: String, path: String): JsonElement {
        http.newCall(request(token, path)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GitHubException(explain(resp, text, token))
            return json.parseToJsonElement(text)
        }
    }

    private fun explain(resp: Response, text: String, token: String): String {
        val msg = runCatching { (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        return when {
            resp.code == 401 -> "GitHub 令牌不对或已过期，到 设置 → GitHub 重新填"
            (resp.code == 403 || resp.code == 429) && (resp.header("X-RateLimit-Remaining") == "0" || "rate limit" in msg.lowercase()) ->
                if (token.isBlank()) "GitHub 限制了访问次数（没填令牌时每小时只能 60 次）。到 设置 → GitHub 填上令牌就能多很多"
                else "GitHub 限制了访问次数，过一会儿再试" + (resp.header("X-RateLimit-Reset")?.toLongOrNull()?.let {
                    "（约 ${((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} 分钟后恢复）"
                } ?: "")
            resp.code == 404 -> "GitHub 上找不到。可能名字写错了；如果是私有项目，要在 设置 → GitHub 填令牌，并给令牌这个项目的读取权限"
            resp.code == 422 && "search" in resp.request.url.encodedPath -> "搜索条件 GitHub 不接受：$msg"
            else -> "GitHub 出错（${resp.code}）" + if (msg.isNotBlank()) "：$msg" else ""
        }
    }

    /** "作者/项目"、项目网址、git 地址 → "作者/项目"。 */
    fun repoOf(s: String): String? {
        val t = s.trim().removeSuffix("/").removeSuffix(".git")
        Regex("""github\.com[/:]([\w.-]+)/([\w.-]+)""").find(t)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        return Regex("""^([\w.-]+)/([\w.-]+)$""").find(t)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
    }

    private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonElement?.o(): JsonObject? = this as? JsonObject

    // ---------- 搜索和列表 ----------

    fun me(token: String): String {
        val u = get(token, "/user").jsonObject
        return u["login"].s() + (u["name"].s().takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
    }

    /** 搜项目：按 stars 或最近更新排。query 可以用 GitHub 的写法，比如 language:kotlin stars:>1000。 */
    fun searchRepos(token: String, query: String, sort: String = ""): String {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val s = when (sort) { "stars", "updated", "forks" -> "&sort=$sort&order=desc"; else -> "" }
        val res = get(token, "/search/repositories?q=$q&per_page=15$s").jsonObject
        val items = res["items"]?.jsonArray.orEmpty()
        if (items.isEmpty()) return "没搜到「$query」相关的项目。可以换英文关键词，或者去掉一些限定条件。"
        return "搜到 ${res["total_count"].s()} 个，前 ${items.size} 个：\n" + items.joinToString("\n") { repoLine(it.jsonObject) }
    }

    /** 搜代码（GitHub 要求登录，所以要令牌）。repo 不空就只在那个项目里搜。 */
    fun searchCode(token: String, query: String, repo: String = ""): String {
        if (token.isBlank()) throw GitHubException("GitHub 搜代码要登录：到 设置 → GitHub 填令牌。或者先把项目下载下来，用 grep 在本地搜")
        val q = java.net.URLEncoder.encode(query.trim() + if (repo.isNotBlank()) " repo:$repo" else "", "UTF-8")
        val res = get(token, "/search/code?q=$q&per_page=20").jsonObject
        val items = res["items"]?.jsonArray.orEmpty()
        if (items.isEmpty()) return "没搜到包含「$query」的代码。"
        return "搜到 ${res["total_count"].s()} 处，前 ${items.size} 个文件：\n" + items.joinToString("\n") { e ->
            val o = e.jsonObject
            "- ${o["repository"].o()?.get("full_name").s()} · ${o["path"].s()}"
        }
    }

    /** 自己的项目（含私有的），最近更新的在前。 */
    fun mine(token: String): String {
        if (token.isBlank()) throw GitHubException("要看你自己的项目（包括私有的），先到 设置 → GitHub 填令牌")
        val list = get(token, "/user/repos?per_page=50&sort=updated&affiliation=owner,collaborator,organization_member").jsonArray
        if (list.isEmpty()) return "这个令牌能看到的项目是空的。如果是细粒度令牌，检查一下「Repository access」有没有选上项目。"
        return "你的项目（最近更新的在前，共 ${list.size} 个）：\n" + list.joinToString("\n") { repoLine(it.jsonObject) }
    }

    private fun repoLine(o: JsonObject): String {
        val tags = listOfNotNull(
            "私有".takeIf { o["private"].s() == "true" },
            o["language"].s().takeIf { it.isNotBlank() },
            "★${o["stargazers_count"].s()}",
            "更新于 ${o["pushed_at"].s().take(10)}",
            "已归档".takeIf { o["archived"].s() == "true" },
        )
        return "- ${o["full_name"].s()}（${tags.joinToString("，")}）" + o["description"].s().takeIf { it.isNotBlank() }?.let { "：${it.take(120)}" }.orEmpty()
    }

    // ---------- 一个项目 ----------

    /** 项目概况：简介、默认分支、大小、根目录、README 开头、最新版本。 */
    fun overview(token: String, repo: String, local: File?): String {
        val r = get(token, "/repos/$repo").jsonObject
        val sb = StringBuilder()
        sb.append("# ${r["full_name"].s()}\n")
        r["description"].s().takeIf { it.isNotBlank() }?.let { sb.append("$it\n") }
        sb.append(listOfNotNull(
            if (r["private"].s() == "true") "私有" else "公开",
            r["language"].s().takeIf { it.isNotBlank() },
            "★${r["stargazers_count"].s()}", "fork ${r["forks_count"].s()}",
            "默认分支 ${r["default_branch"].s()}",
            "约 ${ToolSizes.text((r["size"].s().toLongOrNull() ?: 0) * 1024)}",
            "最近提交 ${r["pushed_at"].s().take(10)}",
            r["license"].o()?.get("spdx_id").s().takeIf { it.isNotBlank() && it != "NOASSERTION" },
        ).joinToString("，")).append("\n")
        r["html_url"].s().let { sb.append("$it\n") }
        if (local != null) sb.append("（已下载到本地，读目录、读文件、grep 都会用本地的）\n")
        sb.append("\n根目录：\n")
        runCatching { get(token, "/repos/$repo/contents").jsonArray }.getOrNull()?.let { list ->
            sb.append(list.sortedBy { if (it.jsonObject["type"].s() == "dir") 0 else 1 }.joinToString("\n") { e ->
                val o = e.jsonObject
                if (o["type"].s() == "dir") "📁 ${o["name"].s()}/" else "📄 ${o["name"].s()}（${ToolSizes.text(o["size"].s().toLongOrNull() ?: 0)}）"
            }).append("\n")
        }
        runCatching { get(token, "/repos/$repo/releases/latest").jsonObject }.getOrNull()?.let { rel ->
            sb.append("\n最新版本：${rel["tag_name"].s()} ${rel["name"].s()}（${rel["published_at"].s().take(10)}），${rel["assets"]?.jsonArray?.size ?: 0} 个附件\n")
        }
        runCatching { raw(token, "/repos/$repo/readme") }.getOrNull()?.let { readme ->
            sb.append("\nREADME 开头：\n").append(readme.take(4000))
            if (readme.length > 4000) sb.append("\n……（README 还有 ${readme.length - 4000} 字，用 read 读 README 看全文）")
        }
        return sb.toString()
    }

    /** 发写请求（POST / PATCH / PUT），body 是 JSON。204 这类没内容的返回空对象。 */
    fun send(token: String, method: String, path: String, body: JsonObject?): JsonElement {
        if (token.isBlank()) throw GitHubException("这一步要改 GitHub 上的东西，先到 设置 → GitHub 填一个有写权限的令牌")
        val rb = (body?.toString() ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = request(token, path).newBuilder().method(method, rb).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GitHubException(explainWrite(resp, text, token))
            return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
        }
    }

    /** 写操作的报错：403 多半是令牌没有写权限。 */
    private fun explainWrite(resp: Response, text: String, token: String): String {
        val msg = runCatching { (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        return when {
            resp.code == 403 && "rate limit" !in msg.lowercase() ->
                "令牌没有这个权限（$msg）。到 GitHub 的令牌设置里给这个项目打开相应的读写权限（代码 Contents、PR Pull requests、Issues、打包 Actions）"
            resp.code == 404 -> "GitHub 上找不到，或者令牌没有这个项目的写权限（$msg）"
            resp.code == 409 -> "GitHub 说有冲突（$msg）"
            resp.code == 422 -> "GitHub 不接受（$msg）"
            else -> explain(resp, text, token)
        }
    }

    /** 拿原始字节（同步时取 GitHub 上的新版本文件，图片之类也不会坏）。 */
    fun bytes(token: String, path: String): ByteArray {
        download.newCall(request(token, path, "application/vnd.github.raw")).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubException(explain(resp, resp.body?.string().orEmpty(), token))
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 拿一段纯文字（比如 Actions 的日志，GitHub 会跳到存日志的地方，令牌不会跟过去）。 */
    fun text(token: String, path: String): String = raw(token, path)

    /** 拿文件原文（README、某个文件）。 */
    private fun raw(token: String, path: String): String {
        http.newCall(request(token, path, "application/vnd.github.raw")).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GitHubException(explain(resp, text, token))
            return text
        }
    }

    /** 目录树（在线）：整棵树一次拿下，按 path 前缀过滤，最多列 400 项。 */
    fun tree(token: String, repo: String, path: String, ref: String): String {
        val branch = ref.ifBlank { get(token, "/repos/$repo").jsonObject["default_branch"].s() }
        val res = get(token, "/repos/$repo/git/trees/${java.net.URLEncoder.encode(branch, "UTF-8")}?recursive=1").jsonObject
        val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val entries = res["tree"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it["path"].s().startsWith(prefix) }
            .map { Triple(it["path"].s(), it["type"].s() == "tree", it["size"].s().toLongOrNull() ?: 0) }
        val truncated = res["truncated"].s() == "true"
        return formatTree("$repo@$branch", prefix, entries) + if (truncated) "\n（项目太大，GitHub 只给了一部分；下载到本地后能看全）" else ""
    }

    /** 读一个文件（在线），带行号；start/end 是行号范围。 */
    fun read(token: String, repo: String, path: String, ref: String, start: Int, end: Int): String {
        val p = path.trim('/').split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val text = raw(token, "/repos/$repo/contents/$p" + if (ref.isNotBlank()) "?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" else "")
        return numbered(path, text, start, end)
    }

    fun releases(token: String, repo: String): String {
        val list = get(token, "/repos/$repo/releases?per_page=10").jsonArray
        if (list.isEmpty()) return "$repo 没有发布过版本（Releases）。要代码的话用 download 下载整个项目。"
        return "$repo 最近的版本：\n" + list.joinToString("\n") { e ->
            val o = e.jsonObject
            val assets = o["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            "## ${o["tag_name"].s()} ${o["name"].s()}（${o["published_at"].s().take(10)}${if (o["prerelease"].s() == "true") "，预览版" else ""}）\n" +
                (if (assets.isEmpty()) "没有附件" else assets.joinToString("\n") { a -> "- ${a["name"].s()}（${ToolSizes.text(a["size"].s().toLongOrNull() ?: 0)}）" })
        }
    }

    // ---------- 下载 ----------

    /** 下载前看看多大：项目按 GitHub 报的仓库大小估，附件按附件大小。 */
    fun repoSize(token: String, repo: String): Long = (get(token, "/repos/$repo").jsonObject["size"].s().toLongOrNull() ?: 0) * 1024

    data class Asset(val id: Long, val name: String, val size: Long)

    fun findAsset(token: String, repo: String, tag: String, name: String): Asset {
        val rel = get(token, if (tag.isBlank()) "/repos/$repo/releases/latest" else "/repos/$repo/releases/tags/${java.net.URLEncoder.encode(tag, "UTF-8")}").jsonObject
        val assets = rel["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (assets.isEmpty()) throw GitHubException("版本 ${rel["tag_name"].s()} 没有附件")
        val a = assets.firstOrNull { it["name"].s() == name } ?: assets.firstOrNull { it["name"].s().contains(name, true) }
            ?: throw GitHubException("版本 ${rel["tag_name"].s()} 里没有「$name」，有这些：" + assets.joinToString("、") { it["name"].s() })
        return Asset(a["id"].s().toLong(), a["name"].s(), a["size"].s().toLongOrNull() ?: 0)
    }

    /** 把网址下到 out；超过 limit 就停下删掉。断了重试一次。返回字节数。 */
    private fun fetch(token: String, path: String, accept: String, out: File, limit: Long): Long {
        var lastError: IOException? = null
        repeat(2) {
            try {
                download.newCall(request(token, path, accept)).execute().use { resp ->
                    if (!resp.isSuccessful) throw GitHubException(explain(resp, resp.body?.string().orEmpty(), token))
                    val body = resp.body ?: throw IOException("没有内容")
                    if (body.contentLength() > limit) throw GitHubException("文件有 ${ToolSizes.text(body.contentLength())}，超过了 ${ToolSizes.text(limit)} 的上限")
                    out.parentFile?.mkdirs()
                    var total = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > limit) throw GitHubException("下载超过了 ${ToolSizes.text(limit)} 的上限，已停下")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    return total
                }
            } catch (e: GitHubException) {
                out.delete(); throw e
            } catch (e: IOException) {
                out.delete(); lastError = e
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    /** 下载 Releases 里的一个附件到 dir（同名加编号）。 */
    fun downloadAsset(token: String, repo: String, asset: Asset, dir: File, limit: Long = HARD_LIMIT): File {
        val out = Workspace.uniqueFile(dir, asset.name)
        fetch(token, "/repos/$repo/releases/assets/${asset.id}", "application/octet-stream", out, limit)
        return out
    }

    /** 本地副本放哪：对话文件夹/.repos/作者__项目名 */
    fun localDir(wsDir: File, repo: String) = File(wsDir, ".repos/" + repo.replace("/", "__"))

    /** 已经下载过就返回本地副本。 */
    fun local(wsDir: File, repo: String): File? = localDir(wsDir, repo).takeIf { File(it, ".jack-repo").exists() }

    data class Downloaded(val dir: File, val files: Int, val bytes: Long, val zipBytes: Long)

    /** 下载整个项目（某个分支或标签的源码压缩包），解压到本地副本，替换旧的。 */
    fun downloadRepo(token: String, repo: String, ref: String, wsDir: File, limit: Long = HARD_LIMIT): Downloaded {
        val dest = localDir(wsDir, repo)
        val zip = File(wsDir, ".repos/.${repo.replace("/", "__")}.zip")
        // 先定下分支和提交：按提交下载，记下来，之后改了代码推回去时据此算改动、查冲突
        val branch = ref.ifBlank { get(token, "/repos/$repo").jsonObject["default_branch"].s() }
        val commit = get(token, "/repos/$repo/commits/${java.net.URLEncoder.encode(branch, "UTF-8")}").jsonObject["sha"].s()
        val zipBytes = fetch(token, "/repos/$repo/zipball/" + commit.ifBlank { java.net.URLEncoder.encode(branch, "UTF-8") },
            "application/vnd.github+json", zip, limit)
        val staging = File(dest.parentFile, ".${dest.name}.new")
        val base = baseDir(wsDir, repo)
        staging.deleteRecursively()
        try {
            val (files, bytes) = unzip(zip, staging, limit * 3)
            File(staging, META).writeText(Meta(repo, branch, commit).toJson())
            // 原样留一份：本地改了什么、推回去时有没有和别人的改动撞上，都和它比
            base.deleteRecursively()
            staging.copyRecursively(base)
            dest.deleteRecursively()
            if (!staging.renameTo(dest)) throw IOException("没能放进对话文件夹")
            return Downloaded(dest, files, bytes, zipBytes)
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    /** 本地副本记着的：哪个项目、哪个分支、下载（或上次推送）时的提交。 */
    data class Meta(val repo: String, val branch: String, val commit: String) {
        fun toJson() = kotlinx.serialization.json.buildJsonObject {
            put("repo", JsonPrimitive(repo)); put("branch", JsonPrimitive(branch)); put("commit", JsonPrimitive(commit))
        }.toString()
    }

    const val META = ".jack-repo"

    fun meta(dir: File): Meta? = runCatching {
        val o = json.parseToJsonElement(File(dir, META).readText()).jsonObject
        Meta(o["repo"].s(), o["branch"].s(), o["commit"].s())
    }.getOrNull()

    /** 下载时的原样副本：.repos/.base/作者__项目名 */
    fun baseDir(wsDir: File, repo: String) = File(wsDir, ".repos/.base/" + repo.replace("/", "__"))

    /**
     * 解压 GitHub 的源码包：去掉包里最外层「作者-项目-提交号/」那一层；
     * 不许解到目标外面（../ 之类），解压后总大小和文件数都有上限（防压缩炸弹）。
     */
    internal fun unzip(zip: File, dest: File, maxBytes: Long, maxFiles: Int = 100_000): Pair<Int, Long> {
        val root = dest.canonicalFile
        root.mkdirs()
        var files = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw GitHubException("压缩包里有不安全的路径（$name），已停止解压")
                if (e.isDirectory) { out.mkdirs(); continue }
                if (++files > maxFiles) throw GitHubException("项目文件超过 $maxFiles 个，太大了")
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw GitHubException("解压后超过 ${ToolSizes.text(maxBytes)}，太大了")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        return files to total
    }

    // ---------- 本地副本 ----------

    /** 不看的目录：依赖、构建产物。 */
    private val SKIP_DIRS = setOf("node_modules", ".git", "build", "dist", ".gradle", "__pycache__", ".idea", "vendor", "target", ".next")

    fun localTree(dir: File, path: String): String {
        val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val entries = dir.walkTopDown().onEnter { it == dir || it.name !in SKIP_DIRS }.filter { it != dir && !it.name.startsWith(".jack") }
            .map { f -> Triple(f.relativeTo(dir).path.replace(File.separatorChar, '/'), f.isDirectory, if (f.isFile) f.length() else 0L) }
            .filter { it.first.startsWith(prefix) }.toList()
        val repo = meta(dir)?.let { "${it.repo}@${it.branch}" } ?: dir.name
        return formatTree("$repo（本地）", prefix, entries)
    }

    fun localRead(dir: File, path: String, start: Int, end: Int): String {
        val f = File(dir, path.trim('/')).canonicalFile
        if (!f.path.startsWith(dir.canonicalPath + File.separator)) throw GitHubException("路径不对：$path")
        if (!f.isFile) throw GitHubException("本地副本里没有文件「$path」，用 tree 看看目录")
        if (!Workspace.isText(f)) throw GitHubException("「$path」不是文字文件（${ToolSizes.text(f.length())}），没法直接读")
        return numbered(path, f.readText(), start, end)
    }

    /** 在本地副本里全文搜：普通文字或正则（regex=true），返回 文件:行号: 那一行，最多 80 处。 */
    fun grep(dir: File, query: String, regex: Boolean, pathPrefix: String = ""): String {
        val pattern = runCatching { if (regex) Regex(query) else Regex(Regex.escape(query), RegexOption.IGNORE_CASE) }
            .getOrElse { throw GitHubException("正则写得不对：${it.message}") }
        val prefix = pathPrefix.trim('/')
        val hits = mutableListOf<String>()
        var filesHit = 0
        var more = false
        dir.walkTopDown().onEnter { it == dir || it.name !in SKIP_DIRS }.filter { it.isFile && it.length() < 2_000_000 && !it.name.startsWith(".jack") }
            .forEach { f ->
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                if (prefix.isNotEmpty() && !rel.startsWith(prefix)) return@forEach
                if (!Workspace.isText(f)) return@forEach
                var hitHere = false
                f.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (pattern.containsMatchIn(line)) {
                            if (!hitHere) { hitHere = true; filesHit++ }
                            if (hits.size < 80) hits += "$rel:${i + 1}: ${line.trim().take(200)}" else more = true
                        }
                    }
                }
            }
        if (hits.isEmpty()) return "本地副本里没找到「$query」。"
        return "找到 ${if (more) "80 多" else hits.size.toString()} 处（$filesHit 个文件）：\n" + hits.joinToString("\n") +
            if (more) "\n……还有更多，缩小范围（path）或换更具体的词" else ""
    }

    /** 本地副本的概况：文件数、大小、各种语言的文件各多少。 */
    fun summary(d: Downloaded): String {
        val byExt = d.dir.walkTopDown().onEnter { it == d.dir || it.name !in SKIP_DIRS }.filter { it.isFile && !it.name.startsWith(".jack") }
            .groupingBy { it.extension.lowercase().ifEmpty { "（无扩展名）" } }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).joinToString("、") { "${it.key} ${it.value}" }
        return "${d.files} 个文件，解压后 ${ToolSizes.text(d.bytes)}（下载 ${ToolSizes.text(d.zipBytes)}）。主要文件类型：$byExt"
    }

    // ---------- 格式 ----------

    private fun formatTree(title: String, prefix: String, entries: List<Triple<String, Boolean, Long>>): String {
        if (entries.isEmpty()) return "$title 里没有「${prefix.trimEnd('/')}」这个目录"
        // 太多就只列前两层，告诉模型往下钻
        val depthLimit = if (entries.size > 400) prefix.count { it == '/' } + 2 else Int.MAX_VALUE
        val shown = entries.filter { it.first.count { c -> c == '/' } < depthLimit }.sortedBy { it.first }.take(400)
        val files = entries.count { !it.second }
        val sb = StringBuilder("$title${if (prefix.isNotEmpty()) " / ${prefix.trimEnd('/')}" else ""}：$files 个文件\n")
        shown.forEach { (p, isDir, size) ->
            val depth = p.removePrefix(prefix).count { it == '/' }
            sb.append("  ".repeat(depth)).append(if (isDir) "📁 ${p.substringAfterLast('/')}/" else "${p.substringAfterLast('/')}（${ToolSizes.text(size)}）").append("\n")
        }
        if (shown.size < entries.size) sb.append("……共 ${entries.size} 项，只列了 ${shown.size} 项；用 path 指定子目录看更深的\n")
        return sb.toString()
    }

    /** 加行号；一次最多给 800 行或 40000 字。 */
    private fun numbered(path: String, text: String, start: Int, end: Int): String {
        val lines = text.lines()
        val from = (if (start > 0) start else 1).coerceIn(1, lines.size.coerceAtLeast(1))
        var to = (if (end > 0) end else lines.size).coerceIn(from, lines.size.coerceAtLeast(from))
        if (to - from > 800) to = from + 799
        val sb = StringBuilder("$path（共 ${lines.size} 行，这里是第 $from–$to 行）：\n")
        for (i in from..to) {
            val line = "${i}\t${lines.getOrElse(i - 1) { "" }}\n"
            if (sb.length + line.length > 40_000) { to = i - 1; sb.append("……太长了，用 start=$i 接着读\n"); return sb.toString() }
            sb.append(line)
        }
        if (to < lines.size) sb.append("……还有 ${lines.size - to} 行，用 start=${to + 1} 接着读\n")
        return sb.toString()
    }
}

/** 文件大小写成人能看的样子。 */
object ToolSizes {
    fun text(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
