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
            put("prefs.json", Store(ctx).exportPrefs().toByteArray())
            JSONS.forEach { n -> File(dir, n).takeIf { it.exists() }?.let { put(n, it.readBytes()) } }
            DIRS.forEach { d ->
                File(dir, d).listFiles()?.filter { it.isFile }?.forEach { f -> put("$d/${f.name}", f.readBytes()) }
            }
        }
    }

    /** 返回导入的条目数。不认识的内容一律跳过。 */
    fun import(ctx: Context, input: InputStream): Int {
        val dir = ctx.filesDir
        var count = 0
        var sawData = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                val ok = !e.isDirectory && !name.contains("..") && (
                    name == "prefs.json" || name in JSONS ||
                        DIRS.any { d -> name.startsWith("$d/") && name.count { it == '/' } == 1 }
                    )
                if (ok) {
                    val bytes = zip.readBytes()
                    if (name == "prefs.json") {
                        Store(ctx).importPrefs(String(bytes))
                    } else {
                        val f = File(dir, name)
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                    }
                    if (name in JSONS || name == "prefs.json") sawData = true
                    count++
                }
                zip.closeEntry()
            }
        }
        if (!sawData) throw IllegalArgumentException("这不是 Jack AI 的备份文件")
        return count
    }
}
