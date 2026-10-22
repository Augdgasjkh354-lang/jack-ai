package com.jack.ai.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * GitHub 的写操作：在下载下来的本地副本里改文件 → 看改动 → 提交并推送；PR、issue、自动打包（Actions）。
 * 推送用 GitHub 的 Git 接口一次提交多个文件，不用 git 命令。
 * 推之前查冲突：要改的文件如果在 GitHub 上也被别人改过，就不推（不会覆盖别人的改动），建议推到新分支再开 PR。
 * 不做：删仓库、删分支、强制推送、改仓库设置。
 */
object GitHubWrite {
    class Change(val path: String, val kind: String) // added / modified / deleted

    private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ---------- 本地副本里改文件 ----------

    /** 本地副本里的文件；不许跑出副本目录，也不许碰 Jack 自己的记录文件。 */
    private fun fileIn(dir: File, path: String): File {
        val p = path.trim().trimStart('/')
        val f = File(dir, p).canonicalFile
        if (p.isEmpty() || !f.path.startsWith(dir.canonicalPath + File.separator) || f.name.startsWith(".jack")) {
            throw GitHub.GitHubException("路径不对：$path")
        }
        return f
    }

    fun write(dir: File, path: String, content: String): String {
        val f = fileIn(dir, path)
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content)
        return (if (existed) "已改写" else "已新建") + "本地文件 ${path.trim('/')}（${content.lines().size} 行）。还没推到 GitHub，用 diff 看改动、commit 推送。"
    }

    fun edit(dir: File, path: String, old: String, new: String, all: Boolean): String {
        val f = fileIn(dir, path)
        if (!f.isFile) throw GitHub.GitHubException("本地副本里没有文件「$path」")
        val (text, n) = TextEdit.replace(f.readText(), old, new, all)
        f.writeText(text)
        return "已在本地 ${path.trim('/')} 里改了 $n 处。还没推到 GitHub，用 diff 看改动、commit 推送。"
    }

    fun remove(dir: File, path: String): String {
        val f = fileIn(dir, path)
        if (!f.exists()) throw GitHub.GitHubException("本地副本里没有「$path」")
        f.deleteRecursively()
        return "已在本地删掉 ${path.trim('/')}。还没推到 GitHub，commit 后才生效。"
    }

    // ---------- 改动 ----------

    private fun files(root: File): Map<String, File> =
        if (!root.isDirectory) emptyMap()
        else root.walkTopDown().filter { it.isFile && !it.name.startsWith(".jack") }
            .associateBy { it.relativeTo(root).path.replace(File.separatorChar, '/') }

    /** 本地副本和原样副本比，哪些文件新增、改了、删了。 */
    fun changes(local: File, base: File): List<Change> {
        val now = files(local)
        val before = files(base)
        val out = mutableListOf<Change>()
        now.forEach { (p, f) ->
            val b = before[p]
            if (b == null) out += Change(p, "added")
            else if (b.length() != f.length() || !b.readBytes().contentEquals(f.readBytes())) out += Change(p, "modified")
        }
        before.keys.filter { it !in now }.forEach { out += Change(it, "deleted") }
        return out.sortedBy { it.path }
    }

    /** 改动写成人能看的样子：每个文件改了哪几行（去掉前后没变的部分），总长有上限。 */
    fun diff(local: File, base: File, maxChars: Int = 12_000): String {
        val list = changes(local, base)
        if (list.isEmpty()) return "本地副本和下载时一样，没有改动。"
        val sb = StringBuilder("${list.size} 个文件有改动：\n")
        list.forEach { c -> sb.append("${mark(c.kind)} ${c.path}\n") }
        list.forEach { c ->
            if (sb.length > maxChars) return@forEach
            val a = File(base, c.path).takeIf { it.isFile }
            val b = File(local, c.path).takeIf { it.isFile }
            if ((a != null && !Workspace.isText(a)) || (b != null && !Workspace.isText(b))) {
                sb.append("\n=== ${c.path}（不是文字文件，${ToolSizes.text(b?.length() ?: 0)}）\n"); return@forEach
            }
            sb.append("\n=== ${c.path}\n").append(hunk(a?.readText().orEmpty(), b?.readText().orEmpty()))
        }
        if (sb.length > maxChars) { sb.setLength(maxChars); sb.append("\n……改动太多，没显示完") }
        return sb.toString()
    }

    private fun mark(kind: String) = when (kind) { "added" -> "+ 新增"; "deleted" -> "- 删除"; else -> "~ 修改" }

    /** 去掉开头和结尾相同的行，剩下中间那段：- 是原来的，+ 是现在的。 */
    internal fun hunk(old: String, new: String, maxLines: Int = 80): String {
        val a = if (old.isEmpty()) emptyList() else old.lines()
        val b = if (new.isEmpty()) emptyList() else new.lines()
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val removed = a.subList(head, a.size - tail)
        val added = b.subList(head, b.size - tail)
        val sb = StringBuilder("@@ 第 ${head + 1} 行起\n")
        var shown = 0
        removed.forEach { if (shown++ < maxLines) sb.append("- ").append(it).append("\n") }
        added.forEach { if (shown++ < maxLines) sb.append("+ ").append(it).append("\n") }
        if (shown > maxLines) sb.append("……这一段共 ${removed.size} 行删、${added.size} 行加，只显示了前 $maxLines 行\n")
        return sb.toString()
    }

    /** git 算文件编号的办法：sha1("blob 长度\0内容")。和 GitHub 上的比，就知道文件有没有被别人改过。 */
    internal fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray())
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // ---------- 提交并推送 ----------

    data class Pushed(val sha: String, val branch: String, val files: Int, val url: String)

    /**
     * 把本地改动提交到 branch（不填就是下载时的分支），一次一个提交。
     * 分支不存在就从下载时的提交新建（这样一定不冲突，适合之后开 PR）。
     * 分支上有新提交时：要改的文件如果也被改过就拒绝，其他文件的新改动保留。
     */
    fun commit(token: String, local: File, base: File, message: String, branch: String): Pushed {
        val meta = GitHub.meta(local) ?: throw GitHub.GitHubException("这个本地副本没有下载记录，重新 download 一次")
        if (meta.commit.isBlank()) throw GitHub.GitHubException("不知道下载时的版本，重新 download 一次")
        val repo = meta.repo
        val target = branch.trim().ifBlank { meta.branch }
        val list = changes(local, base)
        if (list.isEmpty()) throw GitHub.GitHubException("没有改动可以提交")
        val refPath = "/repos/$repo/git/ref/heads/" + target.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val head = runCatching { GitHub.get(token, refPath).jsonObject["object"]?.jsonObject?.get("sha").s() }
            .getOrElse { e -> if (e.message.orEmpty().contains("找不到")) "" else throw e }
        val parent = head.ifBlank { meta.commit }
        val headTree = GitHub.get(token, "/repos/$repo/git/commits/$parent").jsonObject["tree"]?.jsonObject?.get("sha").s()
        val remote = GitHub.get(token, "/repos/$repo/git/trees/$headTree?recursive=1").jsonObject["tree"]?.jsonArray.orEmpty()
            .map { it.jsonObject }.filter { it["type"].s() == "blob" }.associate { it["path"].s() to (it["sha"].s() to it["mode"].s()) }
        // 分支上有我们没见过的提交：要改的文件在那边变了就不推
        if (head.isNotBlank() && head != meta.commit) {
            val clash = list.filter { c ->
                val was = File(base, c.path).takeIf { it.isFile }?.let { blobSha(it.readBytes()) }
                remote[c.path]?.first != was
            }
            if (clash.isNotEmpty()) throw GitHub.GitHubException(
                "${target} 分支在你下载之后又有了新提交，而且这些文件也被改过：" + clash.joinToString("、") { it.path } +
                    "。为了不覆盖别人的改动，这次没推。可以推到一个新分支（branch 填新名字）再开 PR，或者重新 download 后再改。"
            )
        }
        val entries = buildJsonArray {
            list.forEach { c ->
                val mode = remote[c.path]?.second?.takeIf { it.isNotBlank() } ?: "100644"
                add(buildJsonObject {
                    put("path", c.path); put("mode", mode); put("type", "blob")
                    if (c.kind == "deleted") put("sha", JsonNull)
                    else {
                        val bytes = File(local, c.path).readBytes()
                        val blob = GitHub.send(token, "POST", "/repos/$repo/git/blobs", buildJsonObject {
                            put("content", java.util.Base64.getEncoder().encodeToString(bytes))
                            put("encoding", "base64")
                        }).jsonObject["sha"].s()
                        put("sha", blob)
                    }
                })
            }
        }
        val tree = GitHub.send(token, "POST", "/repos/$repo/git/trees", buildJsonObject {
            put("base_tree", headTree); put("tree", entries)
        }).jsonObject["sha"].s()
        val newCommit = GitHub.send(token, "POST", "/repos/$repo/git/commits", buildJsonObject {
            put("message", message.trim()); put("tree", tree); put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }).jsonObject["sha"].s()
        if (head.isBlank()) {
            GitHub.send(token, "POST", "/repos/$repo/git/refs", buildJsonObject { put("ref", "refs/heads/$target"); put("sha", newCommit) })
        } else {
            // 不强制：分支在这期间又被推过，GitHub 会拒绝，不会覆盖
            GitHub.send(token, "PATCH", "/repos/$repo/git/refs/heads/$target", buildJsonObject { put("sha", newCommit); put("force", false) })
        }
        // 推上去了：原样副本跟上，下次只比之后的改动
        list.forEach { c ->
            val dst = File(base, c.path)
            if (c.kind == "deleted") dst.delete() else { dst.parentFile?.mkdirs(); File(local, c.path).copyTo(dst, overwrite = true) }
        }
        File(local, GitHub.META).writeText(GitHub.Meta(repo, target, newCommit).toJson())
        return Pushed(newCommit, target, list.size, "https://github.com/$repo/commit/$newCommit")
    }

    // ---------- PR ----------

    fun prs(token: String, repo: String, state: String): String {
        val list = GitHub.get(token, "/repos/$repo/pulls?state=${state.ifBlank { "open" }}&per_page=20").jsonArray
        if (list.isEmpty()) return "$repo 没有${if (state == "closed") "已关闭的" else "打开着的"} PR。"
        return list.joinToString("\n") { e ->
            val o = e.jsonObject
            "#${o["number"].s()} ${o["title"].s()}（${o["head"]?.jsonObject?.get("ref").s()} → ${o["base"]?.jsonObject?.get("ref").s()}，" +
                "${o["user"]?.jsonObject?.get("login").s()}，${o["updated_at"].s().take(10)}${if (o["draft"].s() == "true") "，草稿" else ""}）"
        }
    }

    fun pr(token: String, repo: String, n: Int): String {
        val o = GitHub.get(token, "/repos/$repo/pulls/$n").jsonObject
        val files = GitHub.get(token, "/repos/$repo/pulls/$n/files?per_page=100").jsonArray
        val comments = GitHub.get(token, "/repos/$repo/issues/$n/comments?per_page=30").jsonArray
        val reviews = GitHub.get(token, "/repos/$repo/pulls/$n/reviews?per_page=30").jsonArray
        return buildString {
            append("PR #$n ${o["title"].s()}\n${o["html_url"].s()}\n")
            append("${o["head"]?.jsonObject?.get("ref").s()} → ${o["base"]?.jsonObject?.get("ref").s()}，状态 ${o["state"].s()}")
            if (o["merged"].s() == "true") append("（已合并）")
            append("，能否合并：${when (o["mergeable"].s()) { "true" -> "能"; "false" -> "有冲突"; else -> "GitHub 还在算" }}\n")
            o["body"].s().takeIf { it.isNotBlank() }?.let { append("\n说明：\n${it.take(3000)}\n") }
            append("\n改动的文件（${files.size} 个）：\n")
            files.forEach { f -> val x = f.jsonObject; append("- ${x["filename"].s()}（${x["status"].s()}，+${x["additions"].s()} -${x["deletions"].s()}）\n") }
            if (reviews.isNotEmpty()) {
                append("\n审查：\n")
                reviews.forEach { r -> val x = r.jsonObject; append("- ${x["user"]?.jsonObject?.get("login").s()}：${x["state"].s()} ${x["body"].s().take(500)}\n") }
            }
            if (comments.isNotEmpty()) {
                append("\n评论：\n")
                comments.forEach { c -> val x = c.jsonObject; append("- ${x["user"]?.jsonObject?.get("login").s()}（${x["created_at"].s().take(10)}）：${x["body"].s().take(800)}\n") }
            }
        }
    }

    fun createPr(token: String, repo: String, title: String, head: String, base: String, body: String): String {
        val baseBranch = base.ifBlank { GitHub.get(token, "/repos/$repo").jsonObject["default_branch"].s() }
        val o = GitHub.send(token, "POST", "/repos/$repo/pulls", buildJsonObject {
            put("title", title); put("head", head); put("base", baseBranch); put("body", body)
        }).jsonObject
        return "已开 PR #${o["number"].s()}：${o["html_url"].s()}"
    }

    fun merge(token: String, repo: String, n: Int, method: String): String {
        val o = GitHub.send(token, "PUT", "/repos/$repo/pulls/$n/merge", buildJsonObject {
            put("merge_method", method.takeIf { it in setOf("merge", "squash", "rebase") } ?: "squash")
        }).jsonObject
        return if (o["merged"].s() == "true") "PR #$n 已合并（提交 ${o["sha"].s().take(7)}）。" else "没合并：${o["message"].s()}"
    }

    // ---------- issue ----------

    fun issues(token: String, repo: String, state: String): String {
        val list = GitHub.get(token, "/repos/$repo/issues?state=${state.ifBlank { "open" }}&per_page=30").jsonArray
            .map { it.jsonObject }.filter { it["pull_request"] == null }
        if (list.isEmpty()) return "$repo 没有${if (state == "closed") "已关闭的" else "打开着的"} issue。"
        return list.joinToString("\n") { o ->
            "#${o["number"].s()} ${o["title"].s()}（${o["user"]?.jsonObject?.get("login").s()}，${o["comments"].s()} 条评论，${o["updated_at"].s().take(10)}）"
        }
    }

    fun issue(token: String, repo: String, n: Int): String {
        val o = GitHub.get(token, "/repos/$repo/issues/$n").jsonObject
        val comments = GitHub.get(token, "/repos/$repo/issues/$n/comments?per_page=50").jsonArray
        return buildString {
            append("#$n ${o["title"].s()}（${o["state"].s()}，${o["user"]?.jsonObject?.get("login").s()}）\n${o["html_url"].s()}\n\n")
            append(o["body"].s().take(8000)).append("\n")
            if (comments.isNotEmpty()) {
                append("\n评论：\n")
                comments.forEach { c -> val x = c.jsonObject; append("- ${x["user"]?.jsonObject?.get("login").s()}（${x["created_at"].s().take(10)}）：${x["body"].s().take(2000)}\n") }
            }
        }
    }

    fun createIssue(token: String, repo: String, title: String, body: String): String {
        val o = GitHub.send(token, "POST", "/repos/$repo/issues", buildJsonObject { put("title", title); put("body", body) }).jsonObject
        return "已新建 issue #${o["number"].s()}：${o["html_url"].s()}"
    }

    fun comment(token: String, repo: String, n: Int, body: String): String {
        val o = GitHub.send(token, "POST", "/repos/$repo/issues/$n/comments", buildJsonObject { put("body", body) }).jsonObject
        return "已在 #$n 下评论：${o["html_url"].s()}"
    }

    // ---------- 自动打包（Actions） ----------

    fun runs(token: String, repo: String): String {
        val list = GitHub.get(token, "/repos/$repo/actions/runs?per_page=8").jsonObject["workflow_runs"]?.jsonArray.orEmpty()
        if (list.isEmpty()) return "$repo 还没有运行过 Actions。"
        return "最近的运行：\n" + list.joinToString("\n") { e ->
            val o = e.jsonObject
            val result = when (o["status"].s()) {
                "completed" -> when (o["conclusion"].s()) { "success" -> "✓ 成功"; "failure" -> "✗ 失败"; "cancelled" -> "已取消"; else -> o["conclusion"].s() }
                "in_progress" -> "进行中"; "queued" -> "排队中"; else -> o["status"].s()
            }
            "- 运行 ${o["id"].s()}｜${o["name"].s()} #${o["run_number"].s()}｜$result｜${o["head_branch"].s()} ${o["head_sha"].s().take(7)}｜" +
                "${o["display_title"].s().take(60)}｜${o["created_at"].s().replace('T', ' ').take(16)}"
        }
    }

    /** 一次运行的各个任务，失败的附上日志最后 120 行。 */
    fun run(token: String, repo: String, id: Long): String {
        val jobs = GitHub.get(token, "/repos/$repo/actions/runs/$id/jobs").jsonObject["jobs"]?.jsonArray.orEmpty()
        return buildString {
            append("运行 $id 的任务：\n")
            jobs.forEach { j ->
                val o = j.jsonObject
                append("- ${o["name"].s()}：${o["status"].s()} ${o["conclusion"].s()}\n")
                o["steps"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it["conclusion"].s() == "failure" }
                    .forEach { s -> append("    失败的步骤：${s["name"].s()}\n") }
                if (o["conclusion"].s() == "failure") {
                    val log = runCatching { GitHub.text(token, "/repos/$repo/actions/jobs/${o["id"].s()}/logs") }.getOrElse { "（日志拿不到：${it.message}）" }
                    // 去掉每行开头的时间戳，只要最后 120 行
                    val tail = log.lines().map { it.replace(Regex("""^\S+Z """), "") }.filter { it.isNotBlank() }.takeLast(120)
                    append("    日志最后 ${tail.size} 行：\n").append(tail.joinToString("\n") { "    $it" }).append("\n")
                }
            }
        }
    }

    fun rerun(token: String, repo: String, id: Long): String {
        GitHub.send(token, "POST", "/repos/$repo/actions/runs/$id/rerun-failed-jobs", null)
        return "已让运行 $id 重跑失败的任务。过几分钟用 runs 看结果。"
    }
}
