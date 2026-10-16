package com.jack.ai.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 把聊天、项目、记忆、梦、用量、设置、图片和生成的文件打包成一个 zip，换手机时再导入。 */
object Backup {
    private val JSONS = listOf("conversations.json", "projects.json", "memories.json", "dreams.json", "usage.json", "routines.json", "library.json", "flashcards.json")
    private val DIRS = listOf("images", "outputs", "library")

    fun export(ctx: Context, out: OutputStream) {
        val dir = ctx.filesDir
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // 文件边读边写，大文件不整个进内存
            fun put(name: String, f: File) {
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            put("prefs.json", Store(ctx).exportPrefs().toByteArray())
            JSONS.forEach { n -> File(dir, n).takeIf { it.exists() }?.let { put(n, it) } }
            DIRS.forEach { d ->
                File(dir, d).listFiles()?.filter { it.isFile }?.forEach { f -> put("$d/${f.name}", f) }
            }
            // 对话文件夹：每个对话一个子目录，底稿在 .src 里；图片缓存不备份
            File(dir, "workspaces").listFiles()?.filter { it.isDirectory }?.forEach { ws ->
                ws.walkTopDown().filter { it.isFile && !it.name.startsWith(".tmp-") && "/.cache/" !in it.path }.forEach { f ->
                    put("workspaces/" + f.relativeTo(File(dir, "workspaces")).invariantSeparatorsPath, f)
                }
            }
        }
    }

    /** 返回导入的条目数。不认识的内容一律跳过。 */
    fun import(ctx: Context, input: InputStream): Int {
        val dir = ctx.filesDir
        val staging = File(ctx.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        // 防坏包：单个文件不超过 200 MB；总量不超过手机剩余空间的一半（解压一份、再复制一份）
        val maxEntry = 200L * 1024 * 1024
        val maxTotal = (dir.usableSpace / 2 - 64L * 1024 * 1024).coerceAtLeast(0)
        var total = 0L
        var count = 0
        var sawData = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    val ok = !e.isDirectory && !name.contains("..") && !name.contains('\\') && (
                        name == "prefs.json" || name in JSONS ||
                            DIRS.any { d -> name.startsWith("$d/") && name.count { it == '/' } == 1 } ||
                            (name.startsWith("workspaces/") && name.count { it == '/' } in 2..3)
                        )
                    if (ok) {
                        val f = File(staging, name)
                        f.parentFile?.mkdirs()
                        var size = 0L
                        f.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                size += n
                                total += n
                                require(size <= maxEntry) { "备份里的「$name」太大了" }
                                require(total <= maxTotal) { "手机剩余空间不够恢复这个备份" }
                                out.write(buf, 0, n)
                            }
                        }
                        // 先检查所有 JSON 都能读，再动手机上的数据
                        if (name in JSONS || name == "prefs.json") {
                            kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
                        }
                        if (name in JSONS || name == "prefs.json") sawData = true
                        count++
                    }
                    zip.closeEntry()
                }
            }
            require(sawData) { "这不是 Jack AI 的备份文件" }
            // 被覆盖的旧文件留一份，只保留最近一次恢复前的
            val rollback = File(ctx.filesDir, "last-restore-rollback").apply { deleteRecursively(); mkdirs() }
            staging.walkTopDown().filter { it.isFile }.forEach { incoming ->
                val relative = incoming.relativeTo(staging).invariantSeparatorsPath
                val target = File(dir, relative)
                if (target.exists()) {
                    val original = File(rollback, relative)
                    original.parentFile?.mkdirs()
                    target.copyTo(original, overwrite = true)
                }
            }
            staging.walkTopDown().filter { it.isFile }.forEach { incoming ->
                val relative = incoming.relativeTo(staging).invariantSeparatorsPath
                // 设置单独导入；对话文件夹里同名的文件照常恢复
                if (relative == "prefs.json") return@forEach
                val target = File(dir, relative)
                target.parentFile?.mkdirs()
                incoming.copyTo(target, overwrite = true)
            }
            File(staging, "prefs.json").takeIf { it.exists() }?.let { Store(ctx).importPrefs(it.readText()) }
            return count
        } finally {
            staging.deleteRecursively()
        }
    }
}
