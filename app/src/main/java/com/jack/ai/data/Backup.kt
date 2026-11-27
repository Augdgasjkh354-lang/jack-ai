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
    /**
     * 整个数据目录都备份，只排除下面这些（以前是手写清单，后来加的手机流程、技能资料、稍后读这些没人记得补，换手机就悄悄丢了）：
     * - 能重新生成或下载的：向量、下载的设计字体、Python 运行时、Linux 沙箱；
     * - 只在这台手机上有意义的：做到一半的断点、闪退记录、待发送的附件、上次恢复留的反悔副本；
     * - 工具包：代码和数据都不带（它的登记表连同 Key 不在备份里，带过去也用不了）；
     * - 对话：手机上一个对话一个文件，备份里另外拼成一整个 conversations.json（新旧版本通用），拆开的和旧的副本不再带。
     * Key、密码在 shared_prefs 和 noBackupFilesDir 里，不在这个目录，本来就不会进来。
     */
    private val SKIP_TOP = setOf(
        "sandbox", "chaquopy", "staging", "checkpoints", "crash_reports", "crash.txt", "last-restore-rollback",
        "library_vectors", "design-fonts", "packs", "pack_data", "pack_store", "test_pack",
        Store.CONV_DIR, "${Store.LEGACY_CONVS}.migrated",
    )
    private val VECTORS = Regex(".*_vectors(-.*)?\\.bin")

    /** 这个相对路径（用 / 分隔）进不进备份。导出、导入用同一条规矩（纯函数，单测）。 */
    internal fun included(relative: String): Boolean {
        if (relative.isEmpty() || '\\' in relative) return false
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." } || parts.size > 12) return false
        val top = parts[0]
        if (top in SKIP_TOP) return false
        if (parts.size == 1 && VECTORS.matches(top)) return false
        // 对话文件夹里的图片缩略图缓存
        if (top == "workspaces" && ".cache" in parts) return false
        val name = parts.last()
        // 写到一半的临时文件、存储层自己留的旧副本和坏文件
        return !(name.startsWith(".tmp-") || name.endsWith(".tmp") || name.endsWith(".prev") || ".corrupt-" in name)
    }

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
            val store = Store(ctx)
            put("prefs.json", store.exportPrefs().toByteArray())
            // 对话在手机上是一个对话一个文件，备份里还是拼成一整个 conversations.json（新旧版本的备份通用）
            zip.putNextEntry(ZipEntry(Store.LEGACY_CONVS))
            store.exportConversations(zip)
            zip.closeEntry()
            dir.walkTopDown().onEnter { d -> d == dir || included(d.relativeTo(dir).invariantSeparatorsPath) }
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(dir).invariantSeparatorsPath
                    // 上面已经单独写过的两个
                    if (rel != Store.LEGACY_CONVS && rel != "prefs.json" && included(rel)) put(rel, f)
                }
        }
    }

    /** 返回导入的条目数。不该在备份里的（见 [included]）一律跳过。 */
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
                    val ok = !e.isDirectory && (name == "prefs.json" || name == Store.LEGACY_CONVS || included(name))
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
                        // 先检查最外层的 JSON（Kavi 自己的数据）都能读，再动手机上的数据；对话文件夹里用户自己的文件不管
                        if ('/' !in name && name.endsWith(".json")) {
                            kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
                            sawData = true
                        }
                        count++
                    }
                    zip.closeEntry()
                }
            }
            require(sawData) { "这不是 Kavi 的备份文件" }
            // 被覆盖的旧文件留一份，只保留最近一次恢复前的
            val rollback = File(ctx.filesDir, "last-restore-rollback").apply { deleteRecursively(); mkdirs() }
            staging.walkTopDown().filter { it.isFile }.forEach { incoming ->
                val relative = incoming.relativeTo(staging).invariantSeparatorsPath
                val target = File(dir, relative)
                // 对话在手机上已经拆成一个对话一个文件：反悔用的那份现拼出来
                if (relative == Store.LEGACY_CONVS) {
                    File(rollback, relative).outputStream().buffered().use { Store(ctx).exportConversations(it) }
                } else if (target.exists()) {
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
                // 先写临时文件再改名：同目录改名是原子的，写一半崩了也不会留半截目标文件；
                // 临时文件名前缀和导出过滤（.tmp-）一致，崩后残留也不会被再导出
                val tmp = File(target.parentFile, ".tmp-restore-${target.name}-${System.nanoTime()}")
                try {
                    incoming.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("恢复「$relative」失败") }
                } catch (e: Exception) { tmp.delete(); throw e }
            }
            File(staging, "prefs.json").takeIf { it.exists() }?.let { Store(ctx).importPrefs(it.readText()) }
            return count
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 把最近一次恢复前备份的旧文件写回去（恢复后发现不对时反悔用）。返回恢复的文件数，没有回滚目录时返回 0。
     * 原来回滚目录只写不读：恢复错了也找不到旧文件。这个函数把读的那一半补上，界面入口（设置里加一个「恢复到上次恢复前」）留给合分支时接。
     */
    fun restoreRollback(ctx: Context): Int {
        val dir = ctx.filesDir
        val rollback = File(dir, "last-restore-rollback")
        if (!rollback.isDirectory) return 0
        var count = 0
        rollback.walkTopDown().filter { it.isFile }.forEach { old ->
            val relative = old.relativeTo(rollback).invariantSeparatorsPath
            val target = File(dir, relative)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".tmp-rollback-${target.name}-${System.nanoTime()}")
            try {
                old.copyTo(tmp, overwrite = true)
                if (tmp.renameTo(target)) count++ else tmp.delete()
            } catch (_: Exception) { tmp.delete() }
        }
        return count
    }
}
