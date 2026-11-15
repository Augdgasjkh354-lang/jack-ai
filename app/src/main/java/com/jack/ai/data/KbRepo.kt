package com.jack.ai.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * 从 GitHub 装知识库。格式（见仓库 docs/knowledge-base-format.md）：
 * - 一个文件夹算一个知识库，里面放 KB.md（开头 --- 里写 name、description，下面是简介），其余的文档（md、txt、pdf、docx……）就是内容，子文件夹也算；
 * - 一个仓库可以放好几个知识库（每个文件夹一个 KB.md）；
 * - 没有 KB.md 的普通仓库也能装：整个仓库算一个知识库，只收文档类的文件。
 * 只读文字，不运行任何东西。
 */
object KbRepo {
    /** 官方知识库仓库：「下载知识库」里默认列它里面的。 */
    const val OFFICIAL = "augdgasjkh354-lang/kavi-knowledge"
    const val MARKER = "KB.md"

    /** 仓库里的一份文档。 */
    data class File(val path: String, val sha: String, val size: Long)

    /** 仓库里找到的一个知识库。dir 为空表示整个仓库（没有 KB.md 时）。 */
    data class Found(val dir: String, val marker: File?, val files: List<File>, val hash: String, val skipped: Int)

    data class Listing(val repo: String, val branch: String, val bases: List<Found>, val truncated: Boolean)

    private val SKIP_DIRS = setOf("node_modules", ".git", ".github", "dist", "build", "out", "vendor", "__pycache__", "target", ".idea", ".vscode")
    /** 普通仓库（没有 KB.md）只收这些：文档类的。 */
    private val DOC_EXT = setOf("md", "markdown", "txt", "rst", "org", "adoc", "tex", "pdf", "docx", "csv", "tsv", "html", "htm")
    /** 有 KB.md 的知识库：作者明说了是资料，结构化数据也收。 */
    private val KB_EXT = DOC_EXT + setOf("json", "yaml", "yml", "xml", "jsonl")
    private val SKIP_NAMES = setOf("license", "license.md", "license.txt", "changelog.md", "contributing.md", "code_of_conduct.md", "security.md")
    /** 一份文档最大多少字节（PDF 允许大一点）。 */
    private const val FILE_MAX = 3L * 1024 * 1024
    private const val PDF_MAX = 20L * 1024 * 1024
    /** 一个知识库最多多少份文档。 */
    const val FILES_MAX = 500

    fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** 列出仓库（某个分支）里的知识库。 */
    fun list(token: String, repo: String, ref: String = ""): Listing {
        val branch = ref.ifBlank { (GitHub.get(token, "/repos/$repo").jsonObject["default_branch"] as? JsonPrimitive)?.content.orEmpty() }
        val res = GitHub.get(token, "/repos/$repo/git/trees/${java.net.URLEncoder.encode(branch, "UTF-8")}?recursive=1").jsonObject
        val blobs = res["tree"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.s("type") == "blob" }
            .map { File(it.s("path"), it.s("sha"), it.s("size").toLongOrNull() ?: 0) }
            .filter { b -> b.path.split('/').dropLast(1).none { it.lowercase() in SKIP_DIRS || it.startsWith(".") } }
        return Listing(repo, branch, find(blobs), (res["truncated"] as? JsonPrimitive)?.content == "true")
    }

    /** 按目录树分出知识库（纯函数，单测）。 */
    fun find(blobs: List<File>): List<Found> {
        val markers = blobs.filter { it.path.substringAfterLast('/').equals(MARKER, true) }
        if (markers.isEmpty()) return listOf(collect("", null, blobs, DOC_EXT, emptyList()))
        val dirs = markers.map { it.path.substringBeforeLast('/', "") }
        return markers.take(100).map { m ->
            val dir = m.path.substringBeforeLast('/', "")
            // 里面还套着别的知识库的，那部分归它自己
            collect(dir, m, blobs, KB_EXT, dirs.filter { it != dir && (dir.isEmpty() || it.startsWith("$dir/")) })
        }
    }

    private fun collect(dir: String, marker: File?, blobs: List<File>, exts: Set<String>, nested: List<String>): Found {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val inside = blobs.filter { b ->
            b != marker && b.path.startsWith(prefix) && nested.none { b.path.startsWith("$it/") } &&
                ext(b.path) in exts && b.path.substringAfterLast('/').lowercase() !in SKIP_NAMES &&
                // 普通仓库根目录的 README 也是资料；有 KB.md 的知识库里 README 一样收
                b.size <= (if (ext(b.path) == "pdf") PDF_MAX else FILE_MAX) && b.size > 0
        }.sortedBy { it.path }
        val files = inside.take(FILES_MAX)
        val hash = MessageDigest.getInstance("SHA-1").digest((listOfNotNull(marker) + files).joinToString("\n") { it.path + "=" + it.sha }.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return Found(dir, marker, files, hash, inside.size - files.size)
    }

    /** 下载一个文件的原始字节：先走 raw.githubusercontent.com（不占 GitHub 接口每小时的次数），不行再走接口。 */
    fun bytes(token: String, repo: String, branch: String, path: String): ByteArray {
        val p = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val b = java.net.URLEncoder.encode(branch, "UTF-8")
        return runCatching { GitHub.bytes(token, "https://raw.githubusercontent.com/$repo/$b/$p") }
            .getOrElse { GitHub.bytes(token, "/repos/$repo/contents/$p?ref=$b") }
    }

    /** 知识库叫什么、讲什么：KB.md 里写的；没有就用文件夹名或项目名。 */
    fun meta(token: String, l: Listing, f: Found): SkillRepo.Parsed {
        val fallback = f.dir.substringAfterLast('/').ifEmpty { l.repo.substringAfter('/') }
        val text = f.marker?.let { runCatching { String(bytes(token, l.repo, l.branch, it.path), Charsets.UTF_8) }.getOrNull() }
        return if (text != null) SkillRepo.parse(text, fallback).let { p -> p.copy(description = p.description.ifBlank { p.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(200) }) }
        else SkillRepo.Parsed(fallback, "来自 GitHub 项目 ${l.repo}", "")
    }

    /** 文档在知识库里显示的名字：相对知识库文件夹的路径。 */
    fun docName(f: Found, path: String) = if (f.dir.isEmpty()) path else path.removePrefix(f.dir + "/")

    fun source(repo: String, branch: String, dir: String) = "$repo@$branch:$dir"
    fun repoOf(source: String) = source.substringBefore('@')
    fun branchOf(source: String) = source.substringAfter('@').substringBefore(':')
    fun dirOf(source: String) = source.substringAfter(':', "")

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
}
