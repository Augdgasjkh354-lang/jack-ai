package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 对话文件夹的存档：每一轮回答开始前记下文件夹的样子（start_消息id），改消息重发、重新回答时先把文件退回那一刻，
 * 免得在上一版改坏的文件上接着做；被换下的那个版本做完时的样子也记一份（end_消息id），切回那个版本时文件跟着回来。
 *
 * 文件按内容去重存一份（blobs/哈希），每个存档只是一张清单，所以每轮记一次也不怎么占地方。
 * 文件太多、太大的文件夹（比如装了一堆依赖的）不记，那时就不退回。
 * 思路参考 AAswordman/Operit 的「重发时工作区回档」（只借思路，代码自己写）。
 */
object Checkpoints {
    const val MAX_FILES = 3000
    const val MAX_BYTES = 200L * 1024 * 1024
    /** 每个文件夹最多留几个存档，多了删最早的。 */
    const val KEEP = 60
    /** 这些目录不记也不动（装的依赖、缓存，能重新装出来）。 */
    private val SKIP_DIRS = setOf("node_modules", "__pycache__", ".venv", "venv", ".gradle", ".cache")

    @Serializable
    data class Entry(val path: String, val sha: String, val size: Long, val modified: Long)

    @Serializable
    data class Manifest(val at: Long, val files: List<Entry>)

    private val json = Json { ignoreUnknownKeys = true }
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lock(store: File) = locks.getOrPut(store.absolutePath) { Any() }

    fun root(ctx: Context) = File(ctx.filesDir, "checkpoints")
    fun store(ctx: Context, key: String) = File(root(ctx), key)

    fun startName(msgId: String) = "start_$msgId"
    fun endName(msgId: String) = "end_$msgId"

    private fun manifestFile(store: File, name: String) = File(store, "$name.json")

    fun has(store: File, name: String) = manifestFile(store, name).isFile

    private fun isLink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 文件夹里要记的文件（相对路径 → 文件），超过上限返回 null。 */
    internal fun walk(ws: File): Map<String, File>? {
        val out = LinkedHashMap<String, File>()
        if (!ws.isDirectory) return out
        var bytes = 0L
        val base = ws.absolutePath.trimEnd('/') + "/"
        val stack = ArrayDeque<File>().apply { add(ws) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (f in d.listFiles().orEmpty()) {
                if (isLink(f)) continue
                if (f.isDirectory) { if (f.name !in SKIP_DIRS) stack.add(f); continue }
                if (!f.isFile) continue
                out[f.absolutePath.removePrefix(base)] = f
                bytes += f.length()
                if (out.size > MAX_FILES || bytes > MAX_BYTES) return null
            }
        }
        return out
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun load(store: File, name: String): Manifest? =
        runCatching { json.decodeFromString(Manifest.serializer(), manifestFile(store, name).readText()) }.getOrNull()

    private fun manifests(store: File): List<File> =
        store.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.lastModified() }.orEmpty()

    /**
     * 把文件夹 [ws] 现在的样子存成 [name]。[overwrite] 为 false 时已经有了就不动（一轮回答的开头只记第一次）。
     * 记不了（文件太多太大、读写出错）返回 false。
     */
    fun save(ws: File, store: File, name: String, overwrite: Boolean = true): Boolean = synchronized(lock(store)) {
        if (!overwrite && has(store, name)) return true
        runCatching {
            val files = walk(ws) ?: return false
            // 大小、修改时间都没变的文件用上次算好的哈希，不再从头读
            val known = HashMap<String, Entry>()
            manifests(store).takeLast(3).forEach { mf ->
                runCatching { json.decodeFromString(Manifest.serializer(), mf.readText()) }.getOrNull()?.files?.forEach { known[it.path] = it }
            }
            val blobs = File(store, "blobs").apply { mkdirs() }
            val entries = files.map { (rel, f) ->
                val size = f.length()
                val modified = f.lastModified()
                val sha = known[rel]?.takeIf { it.size == size && it.modified == modified && File(blobs, it.sha).isFile }?.sha ?: sha(f)
                val blob = File(blobs, sha)
                if (!blob.isFile) {
                    val tmp = File(blobs, "$sha.tmp")
                    f.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(blob)) { tmp.delete(); error("存不进去") }
                }
                Entry(rel, sha, size, modified)
            }
            val mf = manifestFile(store, name)
            val tmp = File(store, "$name.json.tmp")
            tmp.writeText(json.encodeToString(Manifest.serializer(), Manifest(System.currentTimeMillis(), entries)))
            if (!tmp.renameTo(mf)) { mf.delete(); tmp.renameTo(mf) }
            prune(store)
            true
        }.getOrDefault(false)
    }

    /** 存档多了删最早的，再把没有存档用到的文件删掉。 */
    private fun prune(store: File) {
        val list = manifests(store)
        list.dropLast(KEEP).forEach { it.delete() }
        val used = HashSet<String>()
        manifests(store).forEach { mf ->
            runCatching { json.decodeFromString(Manifest.serializer(), mf.readText()) }.getOrNull()?.files?.forEach { used += it.sha }
                ?: return // 有读不了的清单就先不清，免得误删
        }
        File(store, "blobs").listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    /** 退回结果：改了几个文件（新写回、删掉的都算）。 */
    class Restored(val written: Int, val deleted: Int) { val total get() = written + deleted }

    /** 把文件夹 [ws] 退回存档 [name] 的样子；没有这个存档、或文件夹现在太大记不全时返回 null，什么都不动。 */
    fun restore(ws: File, store: File, name: String): Restored? = synchronized(lock(store)) {
        val target = load(store, name) ?: return null
        val now = walk(ws) ?: return null
        val blobs = File(store, "blobs")
        // 要写回的文件内容都在，才开始动
        if (target.files.any { !File(blobs, it.sha).isFile }) return null
        val want = target.files.associateBy { it.path }
        var deleted = 0
        var written = 0
        now.forEach { (rel, f) -> if (rel !in want && f.delete()) deleted++ }
        val base = ws.canonicalFile
        for (e in target.files) {
            val f = File(ws, e.path)
            // 清单里的路径只能落在文件夹里面
            if (!f.canonicalPath.startsWith(base.path + File.separator)) continue
            val cur = now[e.path]
            if (cur != null && cur.length() == e.size && (cur.lastModified() == e.modified || runCatching { sha(cur) }.getOrNull() == e.sha)) continue
            runCatching {
                f.parentFile?.mkdirs()
                File(blobs, e.sha).copyTo(f, overwrite = true)
                f.setLastModified(e.modified)
                written++
            }
        }
        // 删空了的子目录也去掉（跳过的依赖目录不碰）
        fun sweep(d: File) {
            d.listFiles()?.forEach { c -> if (c.isDirectory && !isLink(c) && c.name !in SKIP_DIRS) { sweep(c); if (c.listFiles().isNullOrEmpty()) c.delete() } }
        }
        sweep(ws)
        Restored(written, deleted)
    }

    fun drop(ctx: Context, key: String) { store(ctx, key).deleteRecursively() }
}
