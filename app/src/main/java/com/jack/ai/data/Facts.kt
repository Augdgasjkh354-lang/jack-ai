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
 * 事实存 facts/facts.json；向量单独存 facts/vectors.bin（只追加，定期整理），一条约 1KB。
 * 检索是关键词（BM25）和向量（bge-m3）一起排（RRF 合并）；没配向量服务就只用关键词。
 */
object Facts {
    const val SAME = 0.93f
    const val SIMILAR = 0.80f
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile private var vectors: HashMap<String, ByteArray>? = null
    @Volatile private var vecRecords = 0

    private fun dir(ctx: Context) = File(ctx.filesDir, "facts").apply { mkdirs() }
    private fun factFile(ctx: Context) = File(dir(ctx), "facts.json")
    private fun vecFile(ctx: Context) = File(dir(ctx), "vectors.bin")

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

    fun vectors(ctx: Context): Map<String, ByteArray> = synchronized(lock) {
        vectors ?: HashMap<String, ByteArray>().also { map ->
            var n = 0
            runCatching {
                DataInputStream(vecFile(ctx).inputStream().buffered()).use { s ->
                    while (true) {
                        val id = runCatching { s.readUTF() }.getOrNull() ?: break
                        val dim = s.readInt()
                        if (dim == 0) map.remove(id) else map[id] = ByteArray(dim).also { s.readFully(it) }
                        n++
                    }
                }
            }
            vecRecords = n
            vectors = map
        }
    }

    fun putVector(ctx: Context, id: String, v: FloatArray) = synchronized(lock) {
        val q = quantize(v)
        val map = vectors(ctx) as HashMap
        map[id] = q
        append(ctx) { it.writeUTF(id); it.writeInt(q.size); it.write(q) }
    }

    fun removeVector(ctx: Context, id: String) = synchronized(lock) {
        val map = vectors(ctx) as HashMap
        if (map.remove(id) != null) append(ctx) { it.writeUTF(id); it.writeInt(0) }
    }

    private fun append(ctx: Context, write: (DataOutputStream) -> Unit) {
        DataOutputStream(FileOutputStream(vecFile(ctx), true).buffered()).use(write)
        vecRecords++
        // 删改多了文件里攒了很多旧记录：整份重写一遍
        val live = vectors?.size ?: 0
        if (vecRecords > 200 && vecRecords > live * 2) compact(ctx)
    }

    private fun compact(ctx: Context) {
        val map = vectors ?: return
        val f = vecFile(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { s -> map.forEach { (id, q) -> s.writeUTF(id); s.writeInt(q.size); s.write(q) } }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        vecRecords = map.size
    }

    /** 测试用：清掉内存里的向量缓存。 */
    internal fun resetForTest() { vectors = null; vecRecords = 0 }

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
    fun search(list: List<Fact>, query: String, qv: FloatArray?, vecs: Map<String, ByteArray>, top: Int = 8): List<Fact> {
        val live = list.filter { it.status != "retired" }
        if (live.isEmpty()) return emptyList()
        val byId = live.associateBy { it.id }
        val bm25 = LibraryIndex(live.map { it.id to (it.text + " " + it.topic) }).search(query, 30).map { it.doc }
        val vec = qv?.let { q ->
            val qq = quantize(q)
            live.mapNotNull { f -> vecs[f.id]?.let { f.id to cosine(qq, it) } }.filter { it.second > 0.35f }
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
