package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.EmbedUse
import com.jack.ai.api.Embedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 资料库（含各个知识库）和项目资料的段落向量，让检索能按意思找，不只认关键词。
 * - 按段落文字的指纹存（同样的文字只算一次，文档改了只有变了的段落重算），每个向量来源一份，不同模型的不混着比；
 * - 存在 files/library_vectors/<space>-<来源>.bin，只追加，攒多了旧记录就整理；能重新算，所以不进备份；
 * - 后台慢慢补算：硅基流动按量计费但很便宜，本地模型不花钱但手机算得慢，一次补一批，下次接着补；
 * - 检索是关键词（BM25）和向量一起排（RRF 合并），没有向量来源或还没算好的段落就只靠关键词。
 */
object LibraryVectors {
    const val LIBRARY = "library"
    const val PROJECTS = "projects"

    private val lock = Any()
    private class VecStore(val map: HashMap<String, ByteArray>, var records: Int)
    private val stores = HashMap<String, VecStore>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一次后台补算最多多少段：本地模型算得慢，少一点。 */
    private const val KICK_LOCAL = 400
    private const val KICK_REMOTE = 3000

    fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private fun dir(ctx: Context) = File(ctx.filesDir, "library_vectors").apply { mkdirs() }
    private fun file(ctx: Context, space: String, engine: String) = File(dir(ctx), "$space-${engine.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.bin")

    private fun store(ctx: Context, space: String, engine: String): VecStore = stores.getOrPut("$space/$engine") {
        val map = HashMap<String, ByteArray>()
        var n = 0
        runCatching {
            DataInputStream(file(ctx, space, engine).inputStream().buffered()).use { s ->
                while (true) {
                    val id = runCatching { s.readUTF() }.getOrNull() ?: break
                    val dim = s.readInt()
                    if (dim == 0) map.remove(id) else map[id] = ByteArray(dim).also { s.readFully(it) }
                    n++
                }
            }
        }
        VecStore(map, n)
    }

    /** 某个来源存下的段落向量（键是 [key]）。 */
    fun vectors(ctx: Context, space: String, engine: String): Map<String, ByteArray> = synchronized(lock) { HashMap(store(ctx, space, engine).map) }

    private fun put(ctx: Context, space: String, engine: String, items: List<Pair<String, ByteArray>>) = synchronized(lock) {
        val st = store(ctx, space, engine)
        DataOutputStream(FileOutputStream(file(ctx, space, engine), true).buffered()).use { s ->
            items.forEach { (k, q) -> st.map[k] = q; s.writeUTF(k); s.writeInt(q.size); s.write(q); st.records++ }
        }
    }

    /** 去掉已经没有的段落的向量（文档删了、改了），多出来的旧记录一并整理掉。 */
    fun prune(ctx: Context, space: String, live: Set<String>) = synchronized(lock) {
        dir(ctx).listFiles().orEmpty().filter { it.name.startsWith("$space-") && it.name.endsWith(".bin") }.forEach { f ->
            val engine = f.name.removePrefix("$space-").removeSuffix(".bin")
            val st = store(ctx, space, engine)
            val dead = st.map.keys.filter { it !in live }
            if (dead.isEmpty() && st.records <= st.map.size * 2 + 50) return@forEach
            dead.forEach { st.map.remove(it) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(FileOutputStream(tmp).buffered()).use { s -> st.map.forEach { (k, q) -> s.writeUTF(k); s.writeInt(q.size); s.write(q) } }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            st.records = st.map.size
        }
    }

    /** 补算还没有向量的段落（在后台线程调用）：返回这次补了多少段；出错就停，下次再补。 */
    fun fill(ctx: Context, space: String, e: Embedder.Engine, texts: List<String>, limit: Int): Int {
        val have = vectors(ctx, space, e.id)
        val missing = texts.asSequence().map { key(it) to it }.filter { it.first !in have }.distinctBy { it.first }.take(limit).toList()
        var done = 0
        for (part in missing.chunked(if (e is Embedder.Local) 8 else 32)) {
            val vecs = runCatching { Embedder.embed(e, part.map { it.second }, use = EmbedUse.DOC) }.getOrNull() ?: break
            put(ctx, space, e.id, part.mapIndexed { i, (k, _) -> k to Facts.quantize(vecs[i]) })
            done += part.size
        }
        return done
    }

    /**
     * 在后台把这些段落的向量补上（同一个 space 同时只跑一份）。用第一个向量来源（和检索时优先用的一样）；
     * live 不为空就顺便清掉不在里面的旧向量。
     */
    fun kick(ctx: Context, settings: Settings, space: String, texts: () -> List<String>, live: Boolean = true) {
        val app = ctx.applicationContext
        val e = Embedder.engines(app, settings).firstOrNull() ?: return
        if (!running.add(space)) return
        scope.launch {
            try {
                val all = texts()
                if (live) prune(app, space, all.mapTo(HashSet()) { key(it) })
                fill(app, space, e, all, if (e is Embedder.Local) KICK_LOCAL else KICK_REMOTE)
            } catch (_: Exception) {
            } finally { running.remove(space) }
        }
    }

    /** 有多少段已经有向量了（给界面显示「按意思搜：已备好 x / y 段」）。 */
    fun coverage(ctx: Context, settings: Settings, space: String, texts: List<String>): Pair<Int, Int>? {
        val e = Embedder.engines(ctx, settings).firstOrNull() ?: return null
        val have = vectors(ctx, space, e.id)
        val keys = texts.mapTo(HashSet()) { key(it) }
        return keys.count { it in have } to keys.size
    }

    /** 关键词和向量合起来排（纯函数，单测）：qv 是问题的向量，没有就只用关键词。返回段落在 index.chunks 里的位置。 */
    fun rank(index: LibraryIndex, query: String, qv: FloatArray?, vecs: Map<String, ByteArray>, top: Int, floor: Float): List<Int> {
        val bm25 = index.rank(query, 30)
        if (qv == null || vecs.isEmpty()) return bm25.take(top)
        val q = Facts.quantize(qv)
        val vec = index.chunks.indices.mapNotNull { i -> vecs[key(index.chunks[i].text)]?.let { i to Facts.cosine(q, it) } }
            .filter { it.second > floor }.sortedByDescending { it.second }.take(30).map { it.first }
        return Facts.fuse(bm25.map { it.toString() }, vec.map { it.toString() }).map { it.toInt() }.take(top)
    }

    /**
     * 检索资料：有向量来源、而且已经算过一些段落的向量，就按意思和关键词一起找；否则只用关键词。
     * 顺便在后台把还没算的段落补上。在后台线程调用（问题的向量要联网或在手机上算）。
     */
    fun search(ctx: Context, settings: Settings, space: String, index: LibraryIndex, query: String, top: Int = 6): List<LibraryIndex.Chunk> {
        if (index.chunks.isEmpty() || query.isBlank()) return emptyList()
        var qv: FloatArray? = null
        var vecs: Map<String, ByteArray> = emptyMap()
        var floor = 0.35f
        for (e in Embedder.engines(ctx, settings)) {
            val v = vectors(ctx, space, e.id)
            if (index.chunks.none { key(it.text) in v }) continue
            val q = runCatching { Embedder.embed(e, listOf(query.take(2000)), quick = true, use = EmbedUse.QUERY).firstOrNull() }.getOrNull() ?: continue
            qv = q; vecs = v; floor = e.floor
            break
        }
        kick(ctx, settings, space, { index.chunks.map { it.text } }, live = false)
        return rank(index, query, qv, vecs, top, floor).map { index.chunks[it] }
    }

    /** 测试用。 */
    internal fun resetForTest() = synchronized(lock) { stores.clear() }
}
