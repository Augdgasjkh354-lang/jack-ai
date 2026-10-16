package com.jack.ai.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
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
            put("prefs.json", Store(ctx).exportPrefs().toByteArray())
            JSONS.forEach { n -> File(dir, n).takeIf { it.exists() }?.let { put(n, it.readBytes()) } }
            DIRS.forEach { d ->
                File(dir, d).listFiles()?.filter { it.isFile }?.forEach { f -> put("$d/${f.name}", f.readBytes()) }
            }
            // 对话文件夹：每个对话一个子目录，底稿在 .src 里；图片缓存不备份
            File(dir, "workspaces").listFiles()?.filter { it.isDirectory }?.forEach { ws ->
                ws.walkTopDown().filter { it.isFile && !it.name.startsWith(".tmp-") && "/.cache/" !in it.path }.forEach { f ->
                    put("workspaces/" + f.relativeTo(File(dir, "workspaces")).invariantSeparatorsPath, f.readBytes())
                }
            }
        }
    }

    /** 返回导入的条目数。不认识的内容一律跳过。 */
    fun import(ctx: Context, input: InputStream): Int {
        val dir = ctx.filesDir
        val staging = File(ctx.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        val maxEntry = 32L * 1024 * 1024
        val maxTotal = 256L * 1024 * 1024
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
                                require(size <= maxEntry && total <= maxTotal) { "备份解压后的数据太大" }
                                out.write(buf, 0, n)
                            }
                        }
                        if (name in JSONS) {
                            // Validate all JSON before changing any existing files.
                            kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
                        }
                        if (name == "prefs.json") {
                            kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
                        }
                        if (name in JSONS || name == "prefs.json") sawData = true
                        count++
                    }
                    zip.closeEntry()
                }
            }
            require(sawData) { "这不是 Jack AI 的备份文件" }
            // Keep a rollback copy: imports can replace multiple independent files.
            val rollback = File(ctx.filesDir, "last-restore-rollback").apply { mkdirs() }
            staging.walkTopDown().filter { it.isFile }.forEach { incoming ->
                val relative = incoming.relativeTo(staging).invariantSeparatorsPath
                val target = File(dir, relative)
                if (target.exists()) {
                    val original = File(rollback, relative)
                    original.parentFile?.mkdirs()
                    target.copyTo(original, overwrite = true)
                }
            }
            staging.walkTopDown().filter { it.isFile && it.name != "prefs.json" }.forEach { incoming ->
                val relative = incoming.relativeTo(staging).invariantSeparatorsPath
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
