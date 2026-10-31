package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.Embedder
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** 一条事实的出处：网址、标题、什么时候读到的。 */
@Serializable
data class FactSource(val url: String, val title: String = "", val at: Long = System.currentTimeMillis())

/** 一次修改：改前改后、为什么。 */
@Serializable
data class FactEdit(val at: Long = System.currentTimeMillis(), val old: String, val new: String, val why: String = "")

/**
 * Kavi 的事实库里的一条：一句能独立看懂的短事实，带出处。
 * status：verified 两个以上不同网站说法一致；single 只有一个来源；conflict 和别的事实对不上（两边都留着）；retired 被更正、作废了（留着看历史）。
 */
@Serializable
data class Fact(
    val id: String = newId(),
    val text: String,
    val topic: String = "",
    val sources: List<FactSource> = emptyList(),
    val status: String = "single",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 有时效的（价格、版本、职位）到这个时间就可能过时了；0 = 不过期。 */
    val expiresAt: Long = 0,
    /** 从哪来的：work 做任务时查到的，reading 自己读书时学的。 */
    val origin: String = "work",
    val conflictWith: List<String> = emptyList(),
    /** 被哪条更正了（status 是 retired 时）。 */
    val replacedBy: String = "",
    val history: List<FactEdit> = emptyList(),
)

/**
 * 事实库：只放带外部来源的事实，Kavi 自己推出来的结论不进来（免得错的被当成事实越滚越大）。
 * 事实存 facts/facts.json；向量单独存，每个向量来源一份（只追加，定期整理），一条约 1KB：
 * vectors.bin 是硅基流动 bge-m3 的，vectors-<来源>.bin 是本地模型的（不同模型的向量不能混着比）。
 * 检索是关键词（BM25）和向量一起排（RRF 合并）；没有向量来源就只用关键词。
 */
object Facts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    /** 一个来源的向量：编号 → 向量，records 是文件里的记录数（含删掉的旧记录）。 */
    private class VecStore(val map: HashMap<String, ByteArray>, var records: Int)
    private val stores = HashMap<String, VecStore>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "facts").apply { mkdirs() }
    private fun factFile(ctx: Context) = File(dir(ctx), "facts.json")
    private fun vecFile(ctx: Context, engine: String) = File(dir(ctx), if (engine == Embedder.REMOTE_ID) "vectors.bin" else "vectors-$engine.bin")

    fun load(ctx: Context): List<Fact> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(Fact.serializer()), factFile(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<Fact>) = synchronized(lock) {
        val f = factFile(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Fact.serializer()), list))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ---------- 向量 ----------

    /** 压成 int8（每维一个字节）：余弦相似度不受整体缩放影响，所以不用存比例。 */
    internal fun quantize(v: FloatArray): ByteArray {
        val max = v.maxOf { kotlin.math.abs(it) }.takeIf { it > 0f } ?: 1f
        return ByteArray(v.size) { i -> (v[i] / max * 127f).toInt().coerceIn(-127, 127).toByte() }
    }

    internal fun cosine(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0L; var na = 0L; var nb = 0L
        for (i in a.indices) { val x = a[i].toInt(); val y = b[i].toInt(); dot += x * y; na += x * x; nb += y * y }
        return if (na == 0L || nb == 0L) 0f else (dot / (kotlin.math.sqrt(na.toDouble()) * kotlin.math.sqrt(nb.toDouble()))).toFloat()
    }

    /** 某个来源（Embedder.Engine.id）存下的向量。 */
    fun vectors(ctx: Context, engine: String = Embedder.REMOTE_ID): Map<String, ByteArray> = synchronized(lock) { HashMap(store(ctx, engine).map) }

    private fun store(ctx: Context, engine: String): VecStore = stores.getOrPut(engine) {
        val map = HashMap<String, ByteArray>()
        var n = 0
        runCatching {
            DataInputStream(vecFile(ctx, engine).inputStream().buffered()).use { s ->
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

    fun putVector(ctx: Context, id: String, v: FloatArray, engine: String = Embedder.REMOTE_ID) = synchronized(lock) {
        val q = quantize(v)
        store(ctx, engine).map[id] = q
        append(ctx, engine) { it.writeUTF(id); it.writeInt(q.size); it.write(q) }
    }

    /**
     * 本地模型不花钱：还没有它的向量的事实补算上（换了模型、或以前是用硅基流动存的）。一次最多 limit 条，剩下的下次再补。
     * 手机上算，要在后台线程调用。
     */
    fun backfill(ctx: Context, e: Embedder.Local, list: List<Fact> = load(ctx), limit: Int = 64) {
        val have = vectors(ctx, e.id)
        val missing = list.filter { it.status != "retired" && it.id !in have }.take(limit)
        missing.chunked(16).forEach { part ->
            val vecs = runCatching { Embedder.embed(e, part.map { it.text }) }.getOrNull() ?: return
            part.forEachIndexed { i, f -> putVector(ctx, f.id, vecs[i], e.id) }
        }
    }

    /** 删掉一条事实的向量（每个来源的都删）。 */
    fun removeVector(ctx: Context, id: String) = synchronized(lock) {
        val engines = dir(ctx).listFiles().orEmpty().mapNotNull { f ->
            when {
                f.name == "vectors.bin" -> Embedder.REMOTE_ID
                f.name.startsWith("vectors-") && f.name.endsWith(".bin") -> f.name.removePrefix("vectors-").removeSuffix(".bin")
                else -> null
            }
        }
        engines.forEach { e -> if (store(ctx, e).map.remove(id) != null) append(ctx, e) { it.writeUTF(id); it.writeInt(0) } }
    }

    private fun append(ctx: Context, engine: String, write: (DataOutputStream) -> Unit) {
        DataOutputStream(FileOutputStream(vecFile(ctx, engine), true).buffered()).use(write)
        val st = store(ctx, engine)
        st.records++
        // 删改多了文件里攒了很多旧记录：整份重写一遍
        if (st.records > 200 && st.records > st.map.size * 2) compact(ctx, engine, st)
    }

    private fun compact(ctx: Context, engine: String, st: VecStore) {
        val f = vecFile(ctx, engine)
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { s -> st.map.forEach { (id, q) -> s.writeUTF(id); s.writeInt(q.size); s.write(q) } }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        st.records = st.map.size
    }

    /** 测试用：清掉内存里的向量缓存。 */
    internal fun resetForTest() = synchronized(lock) { stores.clear() }

    // ---------- 规则（纯函数，单测） ----------

    fun host(url: String): String = SiteNotes.hostOf(url) ?: url

    /** 按出处算状态：两个以上不同网站就算核对过。有冲突的、作废的保持原样。 */
    fun statusOf(f: Fact): String = when {
        f.status == "retired" || f.status == "conflict" -> f.status
        f.sources.map { host(it.url) }.distinct().size >= 2 -> "verified"
        else -> "single"
    }

    /** 同一条事实又从别处读到一次：合并出处（同一个网址不重复），重算状态。 */
    fun mergeSources(f: Fact, more: List<FactSource>, now: Long = System.currentTimeMillis()): Fact {
        val all = (f.sources + more).distinctBy { it.url.trimEnd('/') }
        return f.copy(sources = all, updatedAt = now).let { it.copy(status = statusOf(it)) }
    }

    /** 关键词和向量两份排名合成一份（RRF）：两边都靠前的排最前。 */
    fun fuse(vararg rankings: List<String>, k: Int = 60): List<String> {
        val score = HashMap<String, Double>()
        rankings.forEach { r -> r.forEachIndexed { i, id -> score[id] = (score[id] ?: 0.0) + 1.0 / (k + i + 1) } }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** 检索：返回最相关的有效事实（作废的不算）。qv 是问题的向量，没有就只用关键词。 */
    fun search(list: List<Fact>, query: String, qv: FloatArray?, vecs: Map<String, ByteArray>, top: Int = 8, floor: Float = 0.35f): List<Fact> {
        val live = list.filter { it.status != "retired" }
        if (live.isEmpty()) return emptyList()
        val byId = live.associateBy { it.id }
        val bm25 = LibraryIndex(live.map { it.id to (it.text + " " + it.topic) }).search(query, 30).map { it.doc }
        val vec = qv?.let { q ->
            val qq = quantize(q)
            live.mapNotNull { f -> vecs[f.id]?.let { f.id to cosine(qq, it) } }.filter { it.second > floor }
                .sortedByDescending { it.second }.take(30).map { it.first }
        }.orEmpty()
        return fuse(bm25, vec).mapNotNull { byId[it] }.take(top)
    }

    /** 给模型看的一行。 */
    fun line(f: Fact, now: Long = System.currentTimeMillis()): String {
        val state = when {
            f.expiresAt in 1 until now -> "可能过时（" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(f.expiresAt)) + " 到期）"
            f.status == "verified" -> "已核对"
            f.status == "conflict" -> "有冲突" + (if (f.conflictWith.isNotEmpty()) "，对照 " + f.conflictWith.joinToString(", ") { "[f:${it.take(8)}]" } else "")
            else -> "单一来源"
        }
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(f.updatedAt))
        return "[f:${f.id.take(8)}] ${f.text}（$state · $day）\n  来源：" + f.sources.take(4).joinToString(" ") { it.url }
    }
}
