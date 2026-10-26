package com.jack.ai.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户授权给 Kavi 的手机文件夹（设置 → 手机文件夹，用系统的文件选择器选）：只能碰这些文件夹里面的东西。
 * 路径写成「文件夹名/子文件夹/文件名」；只授权了一个文件夹时可以省掉开头的文件夹名。
 */
object PhoneFiles {
    class Missing(msg: String) : Exception(msg)

    /** 存下授权（重启手机也有效）。 */
    fun keep(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun release(ctx: Context, uri: String) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    /** 选好的文件夹叫什么（系统给的显示名）。 */
    fun nameOf(ctx: Context, uri: Uri): String = DocumentFile.fromTreeUri(ctx, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "文件夹"

    /** 授权还在不在（换了手机、恢复备份后就没了）。 */
    fun alive(ctx: Context, f: PhoneFolder): Boolean = runCatching {
        ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == f.uri } && DocumentFile.fromTreeUri(ctx, Uri.parse(f.uri))?.exists() == true
    }.getOrDefault(false)

    /** 拆路径：「文件夹名/a/b」→ (那个文件夹, [a, b])。 */
    fun split(folders: List<PhoneFolder>, path: String): Pair<PhoneFolder, List<String>> {
        if (folders.isEmpty()) throw Missing(NO_FOLDER)
        val parts = path.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw Missing("路径里不能有 ..")
        val first = parts.firstOrNull()
        val hit = first?.let { n -> folders.firstOrNull { it.name == n } ?: folders.firstOrNull { it.name.equals(n, true) } }
        return when {
            hit != null -> hit to parts.drop(1)
            folders.size == 1 -> folders[0] to parts
            else -> throw Missing("路径要以授权的文件夹名开头：" + folders.joinToString("、") { it.name })
        }
    }

    const val NO_FOLDER = "用户还没授权任何手机文件夹。请用户到 设置 → 手机文件夹 点「添加文件夹」选一个（比如「下载」或某个工作文件夹），选好再试。"

    fun root(ctx: Context, f: PhoneFolder): DocumentFile =
        DocumentFile.fromTreeUri(ctx, Uri.parse(f.uri))?.takeIf { alive(ctx, f) }
            ?: throw Missing("「${f.name}」的授权失效了（换手机或恢复备份后会这样），请用户到 设置 → 手机文件夹 重新添加")

    /** 按路径找；找不到抛 Missing（带上那一层有什么）。 */
    fun find(ctx: Context, folders: List<PhoneFolder>, path: String): Pair<PhoneFolder, DocumentFile> {
        val (folder, parts) = split(folders, path)
        var d = root(ctx, folder)
        for ((i, p) in parts.withIndex()) {
            val kids = d.listFiles()
            d = kids.firstOrNull { it.name == p } ?: kids.firstOrNull { it.name.equals(p, true) }
                ?: throw Missing("「${(listOf(folder.name) + parts.take(i)).joinToString("/")}」里没有「$p」。" +
                    kids.take(30).joinToString("、") { it.name.orEmpty() + if (it.isDirectory) "/" else "" }.let { if (it.isEmpty()) "这一层是空的。" else "这一层有：$it" })
        }
        return folder to d
    }

    private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun line(d: DocumentFile, path: String): String =
        if (d.isDirectory) "$path/" else "$path｜${ToolSizes.text(d.length())}｜${TIME.format(Date(d.lastModified()))}"

    /** 列一层（文件夹在前，新的在前），最多 limit 条。 */
    fun list(ctx: Context, folders: List<PhoneFolder>, path: String, limit: Int = 200): String {
        if (path.isBlank() && folders.size > 1) return "授权的文件夹：\n" + folders.joinToString("\n") { "${it.name}/" + if (alive(ctx, it)) "" else "（授权失效了）" }
        val (folder, d) = find(ctx, folders, path)
        if (!d.isDirectory) return line(d, path.trim('/'))
        val base = path.trim().trim('/').ifEmpty { folder.name }.let { if (it == folder.name || it.startsWith(folder.name + "/")) it else folder.name + "/" + it }
        val kids = d.listFiles().sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenByDescending { it.lastModified() })
        if (kids.isEmpty()) return "「$base」是空的。"
        return "「$base」里有 ${kids.size} 项" + (if (kids.size > limit) "（只列了最新的 $limit 项）" else "") + "：\n" +
            kids.take(limit).joinToString("\n") { line(it, "$base/${it.name}") }
    }

    /** 按名字搜（不分大小写，最多翻 5 层、看 3000 项，找到 100 个就停）。 */
    fun search(ctx: Context, folders: List<PhoneFolder>, path: String, query: String): String {
        val roots = if (path.isBlank()) folders.map { it to root(ctx, it) } else listOf(find(ctx, folders, path))
        val q = query.trim().lowercase()
        val hits = mutableListOf<String>()
        var seen = 0
        for ((folder, r) in roots) {
            val queue = ArrayDeque(listOf(r to (if (r.uri.toString() == folder.uri || path.isBlank()) folder.name else path.trim('/')) to 0))
            while (queue.isNotEmpty() && hits.size < 100 && seen < 3000) {
                val (pair, depth) = queue.removeFirst()
                val (d, p) = pair
                for (k in d.listFiles()) {
                    seen++
                    val kp = "$p/${k.name}"
                    if (k.name.orEmpty().lowercase().contains(q)) hits += line(k, kp)
                    if (k.isDirectory && depth < 5) queue.addLast(k to kp to depth + 1)
                }
            }
        }
        return if (hits.isEmpty()) "没找到名字里带「$query」的文件（翻了 $seen 项）。" else "找到 ${hits.size} 个：\n" + hits.joinToString("\n")
    }

    /** 手机里的文件拷进对话文件夹（之后 read_file、convert_file 都能用）。 */
    fun import(ctx: Context, d: DocumentFile, dir: File): File {
        if (d.isDirectory) throw Missing("「${d.name}」是文件夹，要一个个拷文件")
        if (d.length() > 2L * 1024 * 1024 * 1024) throw Missing("「${d.name}」超过 2 GB")
        val f = Workspace.uniqueFile(dir, d.name ?: "文件")
        ctx.contentResolver.openInputStream(d.uri)?.use { i -> f.outputStream().use { i.copyTo(it, 64 * 1024) } } ?: throw Missing("「${d.name}」打不开")
        return f
    }

    /** 对话文件夹里的文件拷到手机文件夹里（同名加编号）。 */
    fun export(ctx: Context, f: File, dir: DocumentFile): DocumentFile {
        if (!dir.isDirectory) throw Missing("目标「${dir.name}」不是文件夹")
        val existing = dir.listFiles().mapNotNull { it.name }.toSet()
        var name = f.name
        var n = 2
        while (name in existing) name = "${f.nameWithoutExtension}($n)" + (if (f.extension.isNotEmpty()) ".${f.extension}" else "").also { n++ }
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        val out = dir.createFile(mime, name) ?: throw Missing("没能在「${dir.name}」里建文件")
        try {
            ctx.contentResolver.openOutputStream(out.uri, "w")?.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } } ?: throw Missing("写不进去")
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return out
    }

    /** 搬家：拷过去再删原来的（跨文件夹的系统接口不一定支持，这样最稳）。只搬文件。 */
    fun move(ctx: Context, d: DocumentFile, to: DocumentFile): DocumentFile {
        if (d.isDirectory) throw Missing("只能搬文件，不能整个搬文件夹")
        if (!to.isDirectory) throw Missing("目标不是文件夹")
        val tmp = File(ctx.cacheDir, "move-${System.nanoTime()}").apply { mkdirs() }
        try {
            val f = File(tmp, d.name ?: "文件")
            ctx.contentResolver.openInputStream(d.uri)?.use { i -> f.outputStream().use { i.copyTo(it, 64 * 1024) } } ?: throw Missing("「${d.name}」打不开")
            val out = export(ctx, f, to)
            if (!d.delete()) { out.delete(); throw Missing("原文件删不掉，没有搬") }
            return out
        } finally {
            tmp.deleteRecursively()
        }
    }
}
