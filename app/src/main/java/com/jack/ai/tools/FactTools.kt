package com.jack.ai.tools

import com.jack.ai.api.EmbedUse
import com.jack.ai.api.Embedder
import com.jack.ai.data.Fact
import com.jack.ai.data.FactEdit
import com.jack.ai.data.FactSource
import com.jack.ai.data.Facts
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 事实库工具 facts：action=recall 查，action=save 存（只收这次真读过的网址做出处）。 */

internal suspend fun ToolSession.recallFacts(args: JsonObject): String {
    val query = args["query"].text().trim()
    if (query.isEmpty()) return "要给出 query（想查的事）。"
    val top = args["top"].text().toIntOrNull()?.coerceIn(1, 20) ?: 8
    return runTool("查事实库：$query") {
        val engines = Embedder.engines(app, settings)
        val text = withContext(Dispatchers.IO) {
            val list = Facts.load(app)
            // 按先后试：哪个来源算出了问题的向量，就和它存的那份比
            var used: Embedder.Engine? = null
            var qv: FloatArray? = null
            for (e in engines) {
                qv = runCatching { Embedder.embed(e, listOf(query), use = EmbedUse.QUERY).first() }.getOrNull()
                if (qv != null) { used = e; break }
            }
            // 本地模型不花钱：还没有向量的事实顺手补上
            if (used is Embedder.Local) Facts.backfill(app, used, list)
            val hits = Facts.search(list, query, qv, used?.let { Facts.vectors(app, it.id) } ?: emptyMap(), top, used?.floor ?: 1f)
            if (hits.isEmpty()) "事实库里没有相关的。需要的话联网查证；查不到就如实说不知道，不要猜。"
            else hits.joinToString("\n") { Facts.line(it) } +
                (if (qv == null) "\n（这次只按关键词找" + (if (engines.isEmpty()) "：没配硅基流动、也没下本地模型，向量检索没开" else "：向量服务没连上") + "）" else "")
        }
        text to SearchRecord("查事实库：$query")
    }
}

/**
 * 存事实：每条一句能独立看懂的话，出处必须是这次任务里真的搜到、读到过的网址（代码核对，不是的不收）。
 * 和已有的说的是同一件事就合并出处（两个网站说法一致就算核对过）；replaces 是更正某条旧的（旧的作废、留历史）；
 * conflicts_with 是和某条对不上（两边都标成有冲突，都留着）。隐私项目里不存。
 */
internal suspend fun ToolSession.saveFact(args: JsonObject): String {
    if (project?.isolated == true) return "这是隐私项目，事实库是共用的，这里查到的东西不存进去。"
    val items = (args["facts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(20)
    if (items.isEmpty()) return "要给出 facts（每条：text、sources）。"
    return runTool("存进事实库：${items.size} 条") {
        val report = withContext(Dispatchers.IO) { storeFacts(items) }
        report to SearchRecord("存进事实库")
    }
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()

private fun ToolSession.storeFacts(items: List<JsonObject>): String {
    val now = System.currentTimeMillis()
    val out = mutableListOf<String>()
    data class New(val text: String, val topic: String, val sources: List<FactSource>, val days: Int, val replaces: String, val conflicts: String)
    val news = items.mapNotNull { o ->
        val text = o.str("text").replace(Regex("\\s+"), " ")
        if (text.length < 6 || text.length > 300) { out += "✗「${text.take(30)}」：一条事实要 6 到 300 字"; return@mapNotNull null }
        val urls = (o["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.startsWith("http") }
        val unseen = Sources.unknown(urls, seenUrls).toSet()
        val ok = urls.filter { it !in unseen }.distinct()
        if (ok.isEmpty()) {
            out += "✗「${text.take(30)}」：出处" + (if (urls.isEmpty()) "没给" else "不是这次真读过的网页（${urls.first().take(60)}）") + "，不收。先读一下来源再存"
            return@mapNotNull null
        }
        New(text, o.str("topic").take(30), ok.map { FactSource(it, at = now) }, o.str("valid_days").toIntOrNull() ?: 0,
            o.str("replaces").removePrefix("f:"), o.str("conflicts_with").removePrefix("f:"))
    }
    if (news.isEmpty()) return out.joinToString("\n")
    val list = Facts.load(app).toMutableList()
    val engines = Embedder.engines(app, settings)
    var engine: Embedder.Engine? = null
    var vecs: List<FloatArray>? = null
    for (e in engines) {
        vecs = runCatching { Embedder.embed(e, news.map { it.text }) }.getOrNull()
        if (vecs != null) { engine = e; break }
    }
    // 本地模型：先把已有事实的向量补齐，查重才查得全
    if (engine is Embedder.Local) Facts.backfill(app, engine, list, limit = 500)
    val existing = engine?.let { Facts.vectors(app, it.id) } ?: emptyMap()
    val same = engine?.same ?: 1f
    val similar = engine?.similar ?: 1f
    fun find(prefix: String) = if (prefix.length < 4) -1 else list.indexOfFirst { it.id.startsWith(prefix) }
    fun norm(s: String) = s.lowercase().replace(Regex("[\\s，。,.；;：:！!？?「」\"'（）()]"), "")
    news.forEachIndexed { k, n ->
        val v = vecs?.getOrNull(k)
        val q = v?.let(Facts::quantize)
        // 和已有的哪条最像
        val ranked = list.withIndex().filter { it.value.status != "retired" }.map { (i, f) ->
            val sim = if (norm(f.text) == norm(n.text)) 1f else q?.let { existing[f.id]?.let { e -> Facts.cosine(q, e) } } ?: 0f
            i to sim
        }.sortedByDescending { it.second }
        val dup = ranked.firstOrNull()?.takeIf { it.second >= same && n.replaces.isEmpty() && n.conflicts.isEmpty() }
        if (dup != null) {
            val merged = Facts.mergeSources(list[dup.first], n.sources, now)
            list[dup.first] = merged
            out += "＝ 已有 [f:${merged.id.take(8)}]，合并出处，现在 ${merged.sources.size} 个来源（${if (merged.status == "verified") "已核对" else "单一来源"}）"
            return@forEachIndexed
        }
        var fact = Fact(text = n.text, topic = n.topic, sources = n.sources, createdAt = now, updatedAt = now,
            expiresAt = if (n.days > 0) now + n.days * 86_400_000L else 0, origin = if (conv.id == com.jack.ai.data.Reader.CONV_ID) "reading" else "work")
        fact = fact.copy(status = Facts.statusOf(fact))
        find(n.replaces).takeIf { it >= 0 }?.let { i ->
            val old = list[i]
            list[i] = old.copy(status = "retired", replacedBy = fact.id, updatedAt = now, history = old.history + FactEdit(now, old.text, n.text, "被更正"))
            fact = fact.copy(history = listOf(FactEdit(now, old.text, n.text, "更正 [f:${old.id.take(8)}]")))
            out += "✎ 更正了 [f:${old.id.take(8)}]"
        }
        find(n.conflicts).takeIf { it >= 0 }?.let { i ->
            val old = list[i]
            list[i] = old.copy(status = "conflict", conflictWith = (old.conflictWith + fact.id).distinct(), updatedAt = now)
            fact = fact.copy(status = "conflict", conflictWith = listOf(old.id))
            out += "⚠ 和 [f:${old.id.take(8)}] 对不上，两条都标成有冲突"
        }
        list += fact
        v?.let { engine?.let { e -> Facts.putVector(app, fact.id, it, e.id) } }
        out += "＋ [f:${fact.id.take(8)}] ${fact.text.take(40)}（${if (fact.status == "verified") "已核对" else if (fact.status == "conflict") "有冲突" else "单一来源"}）"
        // 相近但不完全一样的：可能说的是同一件事、说法不同，让模型看一眼
        ranked.filter { it.second in similar..same }.take(2).forEach { (i, _) ->
            out += "  相近的已有：[f:${list[i].id.take(8)}] ${list[i].text.take(60)}（是同一件事但说法不同，就用 replaces 或 conflicts_with 标出来）"
        }
    }
    Facts.save(app, list)
    if (engine == null) out += if (engines.isEmpty()) "（没配硅基流动、也没下本地模型，这几条没有向量，只能按关键词找到）" else "（向量服务没连上，这几条没有向量，只能按关键词找到）"
    return out.joinToString("\n")
}
