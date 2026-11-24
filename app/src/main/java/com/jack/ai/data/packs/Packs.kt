package com.jack.ai.data.packs

import android.content.Context
import com.jack.ai.data.integrations.GitHub
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 装好的一个工具包。说明文件整份存着：每轮给模型的工具说明不用读文件，也不随文件变。 */
@Serializable
data class InstalledPack(
    val manifest: PackManifest,
    val enabled: Boolean = true,
    /** 从哪装的：file、sample、kavi，或 github:作者/项目@分支:文件夹。 */
    val source: String = "file",
    /** 从 GitHub 装的：当时的提交，检查更新时对比。 */
    val commit: String = "",
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    /** 用户填的配置。随设置加密；带 secret 的不进备份。 */
    val config: Map<String, String> = emptyMap(),
) {
    val id get() = manifest.id
    /** 必填的配置还没填齐。 */
    val missing get() = manifest.config.filter { it.required && config[it.key].isNullOrBlank() }
    val fromGitHub get() = source.startsWith(Packs.GITHUB)
}

/**
 * 工具包的安装和管理：先解到临时文件夹核对（[Staged]，给用户看权限确认页），用户点「装」才放进 packs/。
 * 放在哪：代码 files/packs/<id>/，它自己的数据 files/pack_data/<id>/，键值存储 files/pack_store/<id>.json。
 */
object Packs {
    const val PREFIX = "pack_"
    const val GITHUB = "github:"
    private const val MAX_FILES = 200
    private const val MAX_BYTES = 10L * 1024 * 1024

    fun dir(ctx: Context, id: String) = File(ctx.filesDir, "packs/$id")
    fun dataDir(ctx: Context, id: String) = File(ctx.filesDir, "pack_data/$id")
    fun storeFile(ctx: Context, id: String) = File(ctx.filesDir, "pack_store/$id.json")
    fun icon(ctx: Context, id: String) = File(dir(ctx, id), "icon.png").takeIf { it.isFile }
    fun readme(ctx: Context, id: String) = File(dir(ctx, id), "README.md").takeIf { it.isFile }?.let { runCatching { it.readText().take(20_000) }.getOrNull() }

    /** 解开、核对过，等用户确认的工具包。 */
    data class Staged(
        val manifest: PackManifest,
        val dir: File,
        val source: String,
        val commit: String = "",
        val readme: String = "",
        val files: List<String> = emptyList(),
        val bytes: Long = 0,
        /** 装之前要提醒用户的（Operit 脚本里 Kavi 不支持的那些）。 */
        val warnings: List<String> = emptyList(),
    ) {
        val icon get() = File(dir, "icon.png").takeIf { it.isFile }
    }

    private fun stageRoot(ctx: Context) = File(ctx.cacheDir, "pack_stage").apply { mkdirs() }
    private fun fresh(ctx: Context) = File(stageRoot(ctx), System.nanoTime().toString(36)).apply { mkdirs() }

    /** 从 .zip 装（文件选择器给的流）。说明文件可以在压缩包根上，也可以在唯一的那个文件夹里。 */
    fun stageZip(ctx: Context, input: InputStream, source: String = "file"): Staged {
        val out = fresh(ctx)
        try {
            unzip(input, out)
            return stage(locate(out), source)
        } catch (e: Throwable) {
            out.deleteRecursively(); throw e
        }
    }

    /** 从手机上的文件夹装（Kavi 在对话文件夹里写出来的、App 自带的示例）。 */
    fun stageDir(ctx: Context, from: File, source: String): Staged {
        val out = fresh(ctx)
        try {
            val root = locate(from)
            var n = 0; var size = 0L
            root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") && it.name != "node_modules" }.filter { it.isFile }.forEach { f ->
                if (++n > MAX_FILES) throw PackError(listOf("文件太多了（超过 $MAX_FILES 个）"))
                size += f.length(); if (size > MAX_BYTES) throw PackError(listOf("工具包太大了（超过 10MB）"))
                f.copyTo(File(out, f.relativeTo(root).path), overwrite = true)
            }
            return stage(out, source)
        } catch (e: Throwable) {
            out.deleteRecursively(); throw e
        }
    }

    /** 从 App 自带的 assets/packs/<name> 装（示例工具包）。 */
    fun stageAsset(ctx: Context, name: String): Staged {
        val out = fresh(ctx)
        fun copy(path: String, to: File) {
            val list = ctx.assets.list(path).orEmpty()
            if (list.isEmpty()) ctx.assets.open(path).use { i -> to.parentFile?.mkdirs(); to.outputStream().use { i.copyTo(it) } }
            else list.forEach { copy("$path/$it", File(to, it)) }
        }
        copy("packs/$name", out)
        return stage(out, "sample")
    }

    /**
     * 从 GitHub 装：「作者/项目」，可以带文件夹和分支：「作者/项目/某个文件夹@分支」。
     * 记下当时的提交；没写文件夹时，仓库里只有一个工具包就装它，有好几个就列出来让用户挑。
     */
    fun stageGitHub(ctx: Context, token: String, spec: String): Staged {
        val (repo, path, ref) = parseSpec(spec) ?: throw PackError(listOf("要写成「作者/项目」，比如 jack/kavi-packs，或者 jack/kavi-packs/kuaidi"))
        val branch = ref.ifBlank { (GitHub.get(token, "/repos/$repo").jsonObject["default_branch"] as? JsonPrimitive)?.content.orEmpty() }
        val sha = (GitHub.get(token, "/repos/$repo/commits/${enc(branch)}").jsonObject["sha"] as? JsonPrimitive)?.content.orEmpty()
        val tree = GitHub.get(token, "/repos/$repo/git/trees/$sha?recursive=1").jsonObject["tree"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.content == "blob" }
            .map { (it["path"] as JsonPrimitive).content to ((it["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L) }
        val manifests = tree.map { it.first }.filter { it == PackFormat.FILE || it.endsWith("/" + PackFormat.FILE) }
        val dirOf = { p: String -> p.removeSuffix(PackFormat.FILE).trimEnd('/') }
        val pick = if (path.isNotBlank()) manifests.firstOrNull { dirOf(it) == path.trim('/') }
            ?: throw PackError(listOf("$repo 的「$path」里没有 ${PackFormat.FILE}"))
        else when (manifests.size) {
            0 -> throw PackError(listOf("$repo 里没找到 ${PackFormat.FILE}，它可能不是工具包"))
            1 -> manifests[0]
            else -> manifests.firstOrNull { it == PackFormat.FILE } ?: throw PackError(listOf(
                "$repo 里有好几个工具包，在后面加上文件夹再装：" + manifests.take(8).joinToString("、") { "$repo/${dirOf(it)}" }))
        }
        val dir = dirOf(pick)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val files = tree.filter { (p, _) -> p.startsWith(prefix) && p.removePrefix(prefix).split('/').none { it.startsWith(".") || it == "node_modules" } }
        if (files.size > MAX_FILES) throw PackError(listOf("文件太多了（超过 $MAX_FILES 个）"))
        if (files.sumOf { it.second } > MAX_BYTES) throw PackError(listOf("工具包太大了（超过 10MB）"))
        val out = fresh(ctx)
        try {
            files.forEach { (p, _) ->
                val to = safe(out, p.removePrefix(prefix))
                to.parentFile?.mkdirs()
                to.writeBytes(GitHub.bytes(token, "/repos/$repo/contents/${p.split('/').joinToString("/") { enc(it) }}?ref=$sha"))
            }
            return stage(out, "$GITHUB$repo@$branch:$dir", commit = sha)
        } catch (e: Throwable) {
            out.deleteRecursively(); throw e
        }
    }

    /** GitHub 上的一个文件（Operit 的 .js 脚本）：「作者/项目/路径/x.js[@分支]」或网址（blob/raw 都认）。返回内容和文件名。 */
    fun githubFile(token: String, spec: String): Pair<String, String> {
        val s = spec.trim().replace("/blob/", "/tree/").replace("raw.githubusercontent.com/", "github.com/")
            .let { Regex("github\\.com/([^/]+)/([^/]+)/(?!tree/)([^/]+)/(.+)").find(it)?.let { m -> "github.com/${m.groupValues[1]}/${m.groupValues[2]}/tree/${m.groupValues[3]}/${m.groupValues[4]}" } ?: it }
        val (repo, path, ref) = parseSpec(s) ?: throw PackError(listOf("要写成「作者/项目/路径/脚本.js」"))
        if (path.isBlank()) throw PackError(listOf("要写到 .js 文件的路径"))
        val q = if (ref.isNotBlank()) "?ref=" + enc(ref) else ""
        val bytes = GitHub.bytes(token, "/repos/$repo/contents/${path.split('/').joinToString("/") { enc(it) }}$q")
        if (bytes.size > 2_000_000) throw PackError(listOf("脚本太大了"))
        return String(bytes, Charsets.UTF_8) to path.substringAfterLast('/')
    }

    /** 「作者/项目[/文件夹][@分支]」，也认 GitHub 网址。 */
    fun parseSpec(spec: String): Triple<String, String, String>? {
        var s = spec.trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/").trim('/')
        var ref = ""
        if ('@' in s) { ref = s.substringAfter('@'); s = s.substringBefore('@') }
        // 网址里的 /tree/分支/文件夹
        val parts = s.split('/').filter { it.isNotBlank() }.toMutableList()
        if (parts.size < 2) return null
        if (parts.size >= 4 && parts[2] == "tree") { ref = parts[3]; parts.subList(2, 4).clear() }
        val ok = Regex("^[A-Za-z0-9_.-]+$")
        if (!ok.matches(parts[0]) || !ok.matches(parts[1])) return null
        return Triple("${parts[0]}/${parts[1].removeSuffix(".git")}", parts.drop(2).joinToString("/"), ref)
    }

    /** 核对一个解开的文件夹：说明文件对不对、代码在不在。 */
    internal fun stage(dir: File, source: String, commit: String = ""): Staged {
        val mf = File(dir, PackFormat.FILE)
        if (!mf.isFile) throw PackError(listOf("里面没有 ${PackFormat.FILE}，它可能不是 Kavi 的工具包"))
        val m = PackFormat.parse(mf.readText()).getOrThrow()
        val main = File(dir, m.main)
        if (!main.isFile) throw PackError(listOf("说明里写的代码文件 ${m.main} 不在里面"))
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
        val readme = File(dir, "README.md").takeIf { it.isFile }?.readText()?.take(20_000).orEmpty()
        return Staged(m, dir, source, commit, readme, files, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    /** 说明文件在根上，或者在唯一的那个文件夹里（GitHub 下载的压缩包、别人随手打的包常这样）。 */
    private fun locate(root: File): File {
        if (File(root, PackFormat.FILE).isFile) return root
        val kids = root.listFiles().orEmpty().filter { !it.name.startsWith(".") && it.name != "__MACOSX" }
        val only = kids.singleOrNull()?.takeIf { it.isDirectory }
        if (only != null && File(only, PackFormat.FILE).isFile) return only
        throw PackError(listOf("里面没有 ${PackFormat.FILE}，它可能不是 Kavi 的工具包"))
    }

    private fun unzip(input: InputStream, out: File) {
        var n = 0; var size = 0L
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name.split('/').any { it == "__MACOSX" }) continue
                if (++n > MAX_FILES) throw PackError(listOf("文件太多了（超过 $MAX_FILES 个）"))
                val to = safe(out, e.name)
                to.parentFile?.mkdirs()
                to.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = z.read(buf); if (r < 0) break
                        size += r
                        if (size > MAX_BYTES) throw PackError(listOf("工具包太大了（解开超过 10MB）"))
                        o.write(buf, 0, r)
                    }
                }
            }
        }
        if (n == 0) throw PackError(listOf("压缩包是空的，或者不是 .zip"))
    }

    /** 不许解到外面去（压缩包里的 ../）。 */
    private fun safe(root: File, rel: String): File {
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.canonicalPath + File.separator)) throw PackError(listOf("压缩包里有不安全的路径：$rel"))
        return f
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 用户点了「装」：放进 packs/<id>，替换旧版本；配置和开关沿用旧的。 */
    fun install(ctx: Context, s: Staged, old: InstalledPack?, now: Long = System.currentTimeMillis()): InstalledPack {
        val to = dir(ctx, s.manifest.id)
        to.deleteRecursively()
        to.parentFile?.mkdirs()
        if (!s.dir.renameTo(to)) { s.dir.copyRecursively(to, overwrite = true); s.dir.deleteRecursively() }
        val keep = s.manifest.config.map { it.key }.toSet()
        return InstalledPack(
            manifest = s.manifest, enabled = old?.enabled ?: true, source = s.source, commit = s.commit,
            installedAt = old?.installedAt ?: now, updatedAt = now,
            config = old?.config.orEmpty().filterKeys { it in keep },
        )
    }

    /** 用户没装：清掉临时文件夹。 */
    fun discard(s: Staged) { s.dir.deleteRecursively() }

    fun remove(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
        dataDir(ctx, id).deleteRecursively()
        storeFile(ctx, id).delete()
    }

    fun code(ctx: Context, p: InstalledPack): String? = File(dir(ctx, p.id), p.manifest.main).takeIf { it.isFile }?.readText()

    /** 从 GitHub 装的：仓库里有新提交了没有（有就返回新的提交）。 */
    fun newerCommit(token: String, p: InstalledPack): String? {
        if (!p.fromGitHub) return null
        val repo = p.source.removePrefix(GITHUB).substringBefore('@')
        val branch = p.source.substringAfter('@').substringBefore(':')
        val sha = (GitHub.get(token, "/repos/$repo/commits/${enc(branch)}").jsonObject["sha"] as? JsonPrimitive)?.content.orEmpty()
        return sha.takeIf { it.isNotBlank() && it != p.commit }
    }

    /** 重新装用的来源写法（作者/项目/文件夹@分支）。 */
    fun specOf(p: InstalledPack): String {
        val s = p.source.removePrefix(GITHUB)
        val repo = s.substringBefore('@'); val branch = s.substringAfter('@').substringBefore(':'); val dir = s.substringAfter(':', "")
        return repo + (if (dir.isNotEmpty()) "/$dir" else "") + "@" + branch
    }

    fun sourceLabel(p: InstalledPack): String = when {
        p.fromGitHub -> "GitHub · " + p.source.removePrefix(GITHUB).substringBefore('@')
        p.source == "sample" -> "App 自带的示例"
        p.source == "kavi" -> "Kavi 写的"
        p.source == "operit" || p.source.startsWith("operit:") -> "Operit 脚本"
        else -> "从文件装的"
    }

    // ---------- 交给模型 ----------

    fun active(list: List<InstalledPack>) = list.filter { it.enabled && it.missing.isEmpty() }

    /** 给模型的工具名：pack_ + 工具包 id + __ + 工具名（只留字母数字和 _，接口只认这些），最长 64。 */
    fun toolName(packId: String, tool: String): String = (PREFIX + packId.replace('-', '_') + "__" + tool).take(64)

    fun toolDefs(list: List<InstalledPack>): List<JsonObject> = active(list).flatMap { p ->
        p.manifest.tools.map { t ->
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", toolName(p.id, t.name))
                    put("description", "【工具包「${p.manifest.name}」】" + t.description.take(1000))
                    put("parameters", PackFormat.schema(t))
                })
            }
        }
    }

    fun find(list: List<InstalledPack>, name: String): Pair<InstalledPack, PackToolSpec>? {
        if (!name.startsWith(PREFIX)) return null
        for (p in active(list)) for (t in p.manifest.tools) if (toolName(p.id, t.name) == name) return p to t
        return null
    }
}
