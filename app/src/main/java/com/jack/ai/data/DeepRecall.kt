package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 每轮从深处想起来：关于用户的深处记忆、自己的深处经历，和这句话相关的浮上来。
 * 配了硅基流动就按意思找（bge-m3 向量，换个说法也找得到），再加上关键词命中的；没配或连不上就只用关键词。
 * 向量按「编号 + 文字」缓存在 memory_vectors.bin，文字改了会重算；每轮最多一次向量请求（这句话 + 还没算过的记忆）。
 */
object DeepRecall {
    /** 意思够接近才算想起来（bge-m3 上相关的一般 0.6 以上，不相关的 0.3 到 0.5）。 */
    const val MIN_SIM = 0.6f
    private const val MAX_NEW = 64
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "memory_vectors.bin")

    private fun key(id: String, text: String) = id + ":" + text.hashCode()

    private fun load(ctx: Context): MutableMap<String, ByteArray> = synchronized(lock) {
        val map = HashMap<String, ByteArray>()
        runCatching {
            DataInputStream(file(ctx).inputStream().buffered()).use { s ->
                while (true) {
                    val k = runCatching { s.readUTF() }.getOrNull() ?: break
                    map[k] = ByteArray(s.readInt()).also { s.readFully(it) }
                }
            }
        }
        map
    }

    private fun save(ctx: Context, map: Map<String, ByteArray>) = synchronized(lock) {
        runCatching { DataOutputStream(file(ctx).outputStream().buffered()).use { o -> map.forEach { (k, v) -> o.writeUTF(k); o.writeInt(v.size); o.write(v) } } }
    }

    /** 这句话和每条记忆的意思有多近（键是 key(id, text)）。没配向量服务、连不上、话太短时是空的。 */
    internal suspend fun similarities(ctx: Context, settings: Settings, query: String, docs: List<Pair<String, String>>): Map<String, Float> {
        if (docs.isEmpty() || query.trim().length < 4) return emptyMap()
        val engine = Embedder.engine(settings.providers) ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching {
                val cache = load(ctx)
                val missing = docs.filter { (k, _) -> k !in cache }.take(MAX_NEW)
                val vecs = Embedder.embed(engine, listOf(query.take(2000)) + missing.map { it.second }, quick = true)
                missing.forEachIndexed { i, (k, _) -> cache[k] = Facts.quantize(vecs[i + 1]) }
                // 已经不在的（删了、改了字）顺手清掉
                val live = docs.map { it.first }.toSet()
                if (missing.isNotEmpty() || cache.keys.any { it !in live }) save(ctx, cache.filterKeys { it in live })
                val q = Facts.quantize(vecs[0])
                docs.mapNotNull { (k, _) -> cache[k]?.let { k to Facts.cosine(q, it) } }.toMap()
            }.getOrDefault(emptyMap())
        }
    }

    /** 每轮附加说明里的那段。浮上来的算用到一次（做梦时它们能拿回常记）。 */
    suspend fun note(ctx: Context, settings: Settings, query: String, now: Long = System.currentTimeMillis()): String {
        if (!settings.memoryEnabled || query.isBlank()) return ""
        val ignore = MemoryOps.ignoreFor(settings)
        val deep = MemoryOps.deep(ctx)
        val own = OwnMemory.load(ctx)
        val ownDeep = own.filter { it.deep }
        val sims = similarities(ctx, settings, query, deep.map { key(it.id, it.text) to it.text } + ownDeep.map { key(it.id, it.text) to it.text })
        fun sim(id: String, text: String) = sims[key(id, text)] ?: 0f

        val userHits = (MemoryOps.recall(deep, query, ignore = ignore) + deep.filter { sim(it.id, it.text) >= MIN_SIM })
            .distinctBy { it.id }.sortedByDescending { sim(it.id, it.text) }.take(6)
        // 自己的：问到最近在干嘛时带上最近几件（常记的也算），再加上意思相近的深处经历
        val ownHits = (OwnMemory.select(own, query, includeHot = false) + ownDeep.filter { sim(it.id, it.text) >= MIN_SIM })
            .distinctBy { it.id }.sortedByDescending { sim(it.id, it.text) }.take(6)

        val out = StringBuilder()
        if (userHits.isNotEmpty()) {
            val ids = userHits.map { it.id }.toSet()
            MemoryOps.saveDeep(ctx, MemoryOps.deep(ctx).map { if (it.id in ids) it.copy(lastUsedAt = now, uses = it.uses + 1) else it })
            out.append(Persona.fill("【Also remembered】(from deeper memory, because it seems related; use only if it helps)\n", settings))
                .append(userHits.joinToString("\n") { "- [m:${it.id.take(6)}] ${it.text}" }).append("\n")
        }
        if (ownHits.isNotEmpty()) {
            OwnMemory.markDeepUsed(ctx, ownHits.filter { it.deep }.map { it.id }.toSet(), now)
            out.append(OwnMemory.format(ownHits))
        }
        return out.toString()
    }
}
