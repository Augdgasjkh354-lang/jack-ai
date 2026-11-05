package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest

/**
 * 从 GitHub 仓库装技能、同步更新：认标准的 SKILL.md（Claude Code、Codex 等用的格式，开头 --- 里写 name、description，
 * 下面是做法），技能文件夹里的文字资料（references/… 这类）一起带进来。只读文字，不运行里面的脚本。
 * 思路来自「一份技能、各处都用」（同一个仓库给电脑上的 Agent 和 Kavi 共用）。
 */
object SkillRepo {
    /** 仓库里找到的一个技能。[files]：技能文件夹里要带进来的文字文件（相对路径 → 仓库里的完整路径）。 */
    data class Found(val dir: String, val skillPath: String, val files: Map<String, String>, val hash: String)

    data class Listing(val repo: String, val branch: String, val skills: List<Found>, val truncated: Boolean)

    data class Parsed(val name: String, val description: String, val body: String)

    private val SKIP = setOf("node_modules", ".git", ".github", "test", "tests", "__tests__", "fixtures", "examples", "snapshots", "archive", "dist", "build")
    private val TEXT = setOf("md", "txt", "json", "yaml", "yml", "csv", "py", "html", "css", "xml", "toml", "tsv")
    private const val FILE_MAX = 200_000L
    private val ROOT_DIRS = setOf("references", "reference", "assets", "templates", "resources", "prompts", "knowledge", "knowledge-base", "scripts", "rules")
    private const val FILES_MAX = 40
    const val BODY_MAX = 40_000

    /** 列出仓库（某个分支）里所有的技能：每个 SKILL.md 所在的文件夹算一个。 */
    fun list(token: String, repo: String, ref: String): Listing {
        val branch = ref.ifBlank { (GitHub.get(token, "/repos/$repo").jsonObject["default_branch"] as? JsonPrimitive)?.content.orEmpty() }
        val res = GitHub.get(token, "/repos/$repo/git/trees/${java.net.URLEncoder.encode(branch, "UTF-8")}?recursive=1").jsonObject
        data class Blob(val path: String, val sha: String, val size: Long)
        val blobs = res["tree"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.content == "blob" }
            .map { Blob(it.s("path"), it.s("sha"), it.s("size").toLongOrNull() ?: 0) }
            .filter { b -> b.path.split('/').dropLast(1).none { it.lowercase() in SKIP } }
        val skills = blobs.filter { it.path.substringAfterLast('/').equals("SKILL.md", true) }.take(100).map { sk ->
            val dir = sk.path.substringBeforeLast('/', "")
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            // 文件夹里的文字资料；SKILL.md 在仓库根目录时只带常见的资料文件夹（references、templates……），不把整个仓库算进来
            val inside = blobs.filter { b ->
                b.path.startsWith(prefix) && b != sk && b.size <= FILE_MAX &&
                    (dir.isNotEmpty() || b.path.substringBefore('/', "").lowercase() in ROOT_DIRS) &&
                    b.path.substringAfterLast('.', "").lowercase() in TEXT &&
                    // 里面还套着别的技能的，那部分归它自己
                    blobs.none { o -> o != sk && o.path.substringAfterLast('/').equals("SKILL.md", true) &&
                        o.path.substringBeforeLast('/').let { d -> d != dir && d.startsWith(prefix) && b.path.startsWith("$d/") } }
            }.take(FILES_MAX)
            val hash = MessageDigest.getInstance("SHA-1").digest((listOf(sk) + inside).joinToString("\n") { it.path + "=" + it.sha }.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            Found(dir, sk.path, inside.associate { it.path.removePrefix(prefix) to it.path }, hash)
        }
        return Listing(repo, branch, skills, (res["truncated"] as? JsonPrimitive)?.content == "true")
    }

    fun fetch(token: String, repo: String, branch: String, path: String): String {
        val p = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return GitHub.text(token, "/repos/$repo/contents/$p?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}")
    }

    /** 解析 SKILL.md：开头 --- 之间的 name、description（支持引号和 > | 多行写法），下面是做法。没写名字就用文件夹名。 */
    fun parse(text: String, dirName: String): Parsed {
        val t = text.replace("\r", "").removePrefix("﻿")
        var name = ""; var desc = ""; var body = t
        if (t.startsWith("---")) {
            val end = t.indexOf("\n---", 3)
            if (end > 0) {
                val head = t.substring(3, end).lines()
                body = t.substring(end + 4).substringAfter('\n', "")
                var i = 0
                while (i < head.size) {
                    val line = head[i]
                    val m = Regex("^(name|description):\\s*(.*)$").find(line.trim())
                    if (m != null && !line.startsWith(" ")) {
                        var v = m.groupValues[2].trim()
                        if (v == ">" || v == "|" || v == ">-" || v == "|-" || v.isEmpty()) {
                            val more = ArrayList<String>()
                            while (i + 1 < head.size && (head[i + 1].startsWith(" ") || head[i + 1].isBlank())) { more += head[++i].trim() }
                            v = more.filter { it.isNotEmpty() }.joinToString(if (m.groupValues[2].trim().startsWith("|")) "\n" else " ")
                        }
                        v = v.removeSurrounding("\"").removeSurrounding("'")
                        if (m.groupValues[1] == "name") name = v else desc = v
                    }
                    i++
                }
            }
        }
        return Parsed(name.ifBlank { dirName.ifBlank { "技能" } }.take(40), desc.take(300), body.trim())
    }

    /** 装进来的资料放在这里：files/skill_files/<技能 id>/<相对路径>。 */
    fun dir(ctx: Context, skillId: String) = File(ctx.filesDir, "skill_files/" + skillId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /** 读一份带进来的资料；路径不在这个技能里就是 null。 */
    fun readFile(ctx: Context, skill: Skill, rel: String): String? {
        val want = rel.trim().trimStart('/')
        val name = skill.files.firstOrNull { it == want } ?: skill.files.firstOrNull { it.endsWith("/$want") || it.substringAfterLast('/') == want } ?: return null
        val base = dir(ctx, skill.id).canonicalFile
        val f = File(base, name).canonicalFile
        if (!f.path.startsWith(base.path + File.separator)) return null
        return f.takeIf { it.exists() }?.readText()
    }

    fun source(repo: String, branch: String, dir: String) = "$repo@$branch:$dir"
    fun repoOf(source: String) = source.substringBefore('@')
    fun branchOf(source: String) = source.substringAfter('@').substringBefore(':')
    fun dirOf(source: String) = source.substringAfter(':', "")

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
}
