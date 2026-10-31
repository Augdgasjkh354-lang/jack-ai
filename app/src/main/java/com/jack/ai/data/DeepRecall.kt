package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.EmbedUse
import com.jack.ai.api.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 每轮从深处想起来：关于用户的深处记忆、自己的深处经历，和这句话相关的浮上来。
 * 有向量来源（硅基流动 bge-m3，或手机上的本地模型）就按意思找（换个说法也找得到），再加上关键词命中的；都没有或都连不上就只用关键词。
 * 向量按「编号 + 文字」缓存，每个来源一份（memory_vectors.bin 是 bge-m3 的，本地模型的是 memory_vectors-<来源>.bin），文字改了会重算；
 * 每轮最多算一批（这句话 + 还没算过的记忆）。本地模型的在后台先算好（prefill），不拖慢回答。
 */
object DeepRecall {
    private const val MAX_NEW = 64
    /** 本地模型每轮最多现算几条（手机上一条几十毫秒，太多会拖慢回答；剩下的后台慢慢补）。 */
    private const val MAX_NEW_LOCAL = 16
    private val lock = Any()
    private fun file(ctx: Context, e: Embedder.Engine) =
        File(ctx.filesDir, if (e is Embedder.Remote) "memory_vectors.bin" else "memory_vectors-${e.id}.bin")

    private fun key(id: String, text: String) = id + ":" + text.hashCode()

    private fun load(ctx: Context, e: Embedder.Engine): MutableMap<String, ByteArray> = synchronized(lock) {
        val map = HashMap<String, ByteArray>()
        runCatching {
            DataInputStream(file(ctx, e).inputStream().buffered()).use { s ->
                while (true) {
                    val k = runCatching { s.readUTF() }.getOrNull() ?: break
                    map[k] = ByteArray(s.readInt()).also { s.readFully(it) }
                }
            }
        }
        map
    }

    private fun save(ctx: Context, e: Embedder.Engine, map: Map<String, ByteArray>) = synchronized(lock) {
        runCatching { DataOutputStream(file(ctx, e).outputStream().buffered()).use { o -> map.forEach { (k, v) -> o.writeUTF(k); o.writeInt(v.size); o.write(v) } } }
    }

    /** 这句话和每条记忆的意思有多近（键是 key(id, text)），和这个来源上「算想起来」的门槛。没有向量来源、都连不上、话太短时是空的。 */
    internal suspend fun similarities(ctx: Context, settings: Settings, query: String, docs: List<Pair<String, String>>): Pair<Map<String, Float>, Float> {
        if (docs.isEmpty() || query.trim().length < 4) return emptyMap<String, Float>() to 1f
        return withContext(Dispatchers.IO) {
            for (engine in Embedder.engines(ctx, settings)) {
                val got = runCatching {
                    val cache = load(ctx, engine)
                    val missing = docs.filter { (k, _) -> k !in cache }.take(if (engine is Embedder.Local) MAX_NEW_LOCAL else MAX_NEW)
                    // 硅基流动不分问题和资料，一次请求一起算；本地模型两种前缀不同，分开算
                    val vecs = if (engine is Embedder.Remote) Embedder.embed(engine, listOf(query.take(2000)) + missing.map { it.second }, quick = true)
                    else Embedder.embed(engine, listOf(query.take(2000)), use = EmbedUse.QUERY) +
                        (if (missing.isEmpty()) emptyList() else Embedder.embed(engine, missing.map { it.second }, use = EmbedUse.DOC))
                    val q = vecs[0]
                    missing.forEachIndexed { i, (k, _) -> cache[k] = Facts.quantize(vecs[i + 1]) }
                    // 已经不在的（删了、改了字）顺手清掉
                    val live = docs.map { it.first }.toSet()
                    if (missing.isNotEmpty() || cache.keys.any { it !in live }) save(ctx, engine, cache.filterKeys { it in live })
                    val qq = Facts.quantize(q)
                    docs.mapNotNull { (k, _) -> cache[k]?.let { k to Facts.cosine(qq, it) } }.toMap()
                }.getOrNull()
                if (got != null) return@withContext got to engine.recall
            }
            emptyMap<String, Float>() to 1f
        }
    }

    /** 深处记忆（用户的和自己的）的 (键, 文字)。 */
    private fun deepDocs(ctx: Context): List<Pair<String, String>> =
        MemoryOps.deep(ctx).map { key(it.id, it.text) to it.text } + OwnMemory.load(ctx).filter { it.deep }.map { key(it.id, it.text) to it.text }

    /** 用本地模型时，后台把还没算过的深处记忆都先算好（几条一批，中途让出给要紧的请求）。 */
    fun prefill(ctx: Context, settings: Settings) {
        val engine = Embedder.local(ctx, settings) ?: return
        val docs = deepDocs(ctx)
        val cache = load(ctx, engine)
        val missing = docs.filter { (k, _) -> k !in cache }
        if (missing.isEmpty()) return
        for (part in missing.chunked(8)) {
            // 中途出错就停下，已经算好的照样存
            val vecs = runCatching { Embedder.embed(engine, part.map { it.second }, use = EmbedUse.DOC) }.getOrNull() ?: break
            part.forEachIndexed { i, (k, _) -> cache[k] = Facts.quantize(vecs[i]) }
        }
        val live = docs.map { it.first }.toSet()
        // 存之前再读一次：这段时间里聊天现算的也留着
        save(ctx, engine, (load(ctx, engine) + cache).filterKeys { it in live })
    }

    /** 每轮附加说明里的那段。浮上来的算用到一次（做梦时它们能拿回常记）。 */
    suspend fun note(ctx: Context, settings: Settings, query: String, now: Long = System.currentTimeMillis()): String {
        if (!settings.memoryEnabled || query.isBlank()) return ""
        val ignore = MemoryOps.ignoreFor(settings)
        val deep = MemoryOps.deep(ctx)
        val own = OwnMemory.load(ctx)
        val ownDeep = own.filter { it.deep }
        val (sims, minSim) = similarities(ctx, settings, query, deep.map { key(it.id, it.text) to it.text } + ownDeep.map { key(it.id, it.text) to it.text })
        fun sim(id: String, text: String) = sims[key(id, text)] ?: 0f

        val userHits = (MemoryOps.recall(deep, query, ignore = ignore) + deep.filter { sim(it.id, it.text) >= minSim })
            .distinctBy { it.id }.sortedByDescending { sim(it.id, it.text) }.take(6)
        // 自己的：问到最近在干嘛时带上最近几件（常记的也算），再加上意思相近的深处经历
        val ownHits = (OwnMemory.select(own, query, includeHot = false) + ownDeep.filter { sim(it.id, it.text) >= minSim })
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
