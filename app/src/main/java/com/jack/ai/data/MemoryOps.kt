package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 做梦改记忆不再整份重写，而是提交一张修改单：一条条新增 / 修改 / 作废 / 合并 / 放到深处 / 拿回来，各带理由和出处。
 * 代码来执行：改前先存一版（能回退），作废的挪进存档（不删），一次改动太多就整次不做、第二天重新整理。
 *
 * 记忆分两层：常记（每次都带着，有预算 [HOT_BUDGET]）和深处（不常用但仍然成立，平时不占上下文，聊到相关的自动浮上来，见 DeepRecall）。
 * 上下文有限：常记放不下时，不常用的放到深处，重复的合并，不再成立的才作废。
 */
object MemoryOps {
    /** 常记的预算（token，约 60 来句）。 */
    const val HOT_BUDGET = 1500
    /** own：针对 Kavi 自己的经历（编号 [o:]），交给 OwnMemory.applyOps，这里跳过。 */
    data class Op(val op: String, val id: String = "", val text: String = "", val why: String = "", val source: String = "", val ids: List<String> = emptyList(), val own: Boolean = false)
    data class Result(
        val live: List<MemoryItem>, val archived: List<ArchivedMemory>, val added: Int, val updated: Int, val invalidated: Int, val skipped: Int,
        val deep: List<MemoryItem> = emptyList(), val demoted: Int = 0, val promoted: Int = 0, val merged: Int = 0,
    )

    /** 一次最多动多少条已有的（修改 + 作废 + 合并掉的）：超过就整次不做。放到深处、拿回来能撤回，不算在里面。 */
    fun limit(liveSize: Int) = maxOf(5, (liveSize * 0.3).toInt())

    private val OPS = setOf("add", "update", "invalidate", "demote", "promote", "merge")

    fun parse(arr: JsonArray?): List<Op> = arr.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
        val op = s("op").lowercase().takeIf { it in OPS } ?: return@mapNotNull null
        val rawIds = (o["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
        val own = s("id").startsWith("o:") || rawIds.any { it.startsWith("o:") }
        Op(op, s("id").removePrefix("m:").removePrefix("o:"), s("text"), s("why"), s("source"), rawIds.map { it.removePrefix("m:").removePrefix("o:") }, own)
    }

    /** 这张修改单动到的已有记忆是不是太多了。 */
    fun tooMany(liveSize: Int, ops: List<Op>): Boolean =
        ops.filterNot { it.own }.sumOf { when (it.op) { "update", "invalidate" -> 1; "merge" -> it.ids.size.coerceAtLeast(1); else -> 0 }.toInt() } > limit(liveSize)

    /** 估 token：中文约 0.6 个一字，其他约 0.3 个（和 PromptBuilder.estTokens 一样）。 */
    fun estTokens(s: String): Int {
        var zh = 0
        for (ch in s) if (ch.code in 0x3000..0x9fff || ch.code in 0xff00..0xffef) zh++
        return (zh * 0.6 + (s.length - zh) * 0.3).toInt()
    }

    fun hotTokens(live: List<MemoryItem>) = live.sumOf { estTokens(it.text) + 4 }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s，。,.；;：:！!？?「」\"'（）()]"), "")

    /** 在现在的记忆上执行（期间用户删掉、改过的照现在的算；找不到的编号跳过）。 */
    fun apply(live: List<MemoryItem>, ops: List<Op>, now: Long = System.currentTimeMillis(), deepNow: List<MemoryItem> = emptyList()): Result {
        val list = live.toMutableList()
        val deep = deepNow.toMutableList()
        val archived = mutableListOf<ArchivedMemory>()
        var added = 0; var updated = 0; var invalidated = 0; var skipped = 0; var demoted = 0; var promoted = 0; var merged = 0
        fun find(id: String) = if (id.length < 4) -1 else list.indexOfFirst { it.id.startsWith(id) }
        fun findDeep(id: String) = if (id.length < 4) -1 else deep.indexOfFirst { it.id.startsWith(id) }
        for (op in ops.filterNot { it.own }) when (op.op) {
            "add" -> {
                if (op.text.length < 4 || list.any { norm(it.text) == norm(op.text) }) { skipped++; continue }
                // 深处已经有这一条：拿回来就行
                val d = deep.indexOfFirst { norm(it.text) == norm(op.text) }
                if (d >= 0) { list += deep.removeAt(d).copy(lastUsedAt = now); promoted++; continue }
                list += MemoryItem(text = op.text, createdAt = now, source = op.source); added++
            }
            "update" -> {
                val i = find(op.id)
                if (i < 0 || op.text.length < 4) { skipped++; continue }
                val m = list[i]
                if (norm(m.text) == norm(op.text)) { skipped++; continue }
                list[i] = m.copy(text = op.text, source = op.source.ifBlank { m.source }, history = m.history + MemEdit(now, m.text, op.text, op.why, op.source)); updated++
            }
            "invalidate" -> {
                val i = find(op.id)
                if (i >= 0) { archived += ArchivedMemory(list.removeAt(i), now, op.why, op.source); invalidated++; continue }
                // 深处的也能作废
                val d = findDeep(op.id)
                if (d < 0) { skipped++; continue }
                archived += ArchivedMemory(deep.removeAt(d), now, op.why, op.source); invalidated++
            }
            "demote" -> {
                val i = find(op.id)
                if (i < 0) { skipped++; continue }
                deep += list.removeAt(i); demoted++
            }
            "promote" -> {
                val d = findDeep(op.id)
                if (d < 0) { skipped++; continue }
                list += deep.removeAt(d).copy(lastUsedAt = now); promoted++
            }
            "merge" -> {
                // 几条说的是一回事：合成一条（留在第一条的位置，其余的进存档并注明合进了哪条）
                val idx = op.ids.map { find(it) }.filter { it >= 0 }.distinct()
                if (idx.size < 2 || op.text.length < 4) { skipped++; continue }
                val first = list[idx.first()]
                val others = idx.drop(1).map { list[it] }
                list[idx.first()] = first.copy(text = op.text, source = op.source.ifBlank { first.source },
                    uses = first.uses + others.sumOf { it.uses }, lastUsedAt = maxOf(first.lastUsedAt, others.maxOf { it.lastUsedAt }),
                    history = first.history + MemEdit(now, (listOf(first) + others).joinToString(" / ") { it.text }, op.text, op.why.ifBlank { "合并" }, op.source))
                others.forEach { o -> archived += ArchivedMemory(o, now, "合并进了「${op.text.take(30)}」", op.source) }
                list.removeAll { o -> others.any { it.id == o.id } }
                merged += others.size
            }
        }
        return Result(list, archived, added, updated, invalidated, skipped, deep, demoted, promoted, merged)
    }

    /** 虚字：带它们的两字组（「了一」「的橘」）谁都有，不算数。 */
    private const val STOP_CHARS = "的了一是在我你他她它这那个有不和也就都很把被吗呢吧啊只些么"
    private val STOP_WORDS = setOf("the", "and", "to", "of", "is", "in", "it", "for", "on", "with", "that", "this", "be", "are", "was", "user")

    /** 一段话里有分量的词：两字组去掉带虚字的、英文去掉常用词，再去掉 ignore（用户的名字、「用户」这类每条都有的）。 */
    fun keyTokens(text: String, ignore: Set<String> = emptySet()): Set<String> =
        LibraryIndex.tokens(text).filter { t -> t !in ignore && t !in STOP_WORDS && t.none { it in STOP_CHARS } }.toSet()

    /** 每条记忆都有的词：用户的名字和「用户」。 */
    fun ignoreFor(settings: Settings): Set<String> = (LibraryIndex.tokens(Persona.name(settings)) + listOf("用户", "user", "users")).toSet()

    /** 记忆和这段文字（一段聊天）有没有关系：有分量的词共有两个以上、占这条记忆的三成以上，就算用到了。 */
    fun related(memory: String, tokensOfText: Set<String>, ignore: Set<String> = emptySet()): Boolean {
        val mt = keyTokens(memory, ignore)
        if (mt.isEmpty()) return false
        val shared = mt.count { it in tokensOfText }
        return shared >= 2 && shared.toDouble() / mt.size >= 0.3
    }

    /** 这段聊天用到的记忆：记下时间、次数加一。 */
    fun markUsed(live: List<MemoryItem>, text: String, now: Long = System.currentTimeMillis(), ignore: Set<String> = emptySet()): List<MemoryItem> {
        val tt = LibraryIndex.tokens(text).toSet()
        return live.map { m -> if (related(m.text, tt, ignore)) m.copy(lastUsedAt = now, uses = m.uses + 1) else m }
    }

    /** 从深处找和这句话最相关的几条。 */
    fun recall(deep: List<MemoryItem>, query: String, top: Int = 3, ignore: Set<String> = emptySet()): List<MemoryItem> {
        if (deep.isEmpty() || query.isBlank()) return emptyList()
        val qt = keyTokens(query, ignore)
        if (qt.isEmpty()) return emptyList()
        val hits = LibraryIndex(deep.map { it.id to it.text }).search(query, top * 3).map { it.doc }.distinct()
        // 关键词检索容易沾边：至少共有两个有分量的词才算
        return hits.mapNotNull { id -> deep.firstOrNull { it.id == id } }
            .filter { m -> keyTokens(m.text, ignore).count { it in qt } >= 2 }.take(top)
    }

    /** 给模型看的记忆：带短编号、出处、多久没用了、用过几次。 */
    fun listing(live: List<MemoryItem>, now: Long = System.currentTimeMillis()): String = if (live.isEmpty()) "(none)\n" else live.joinToString("") { m ->
        val used = if (m.lastUsedAt > 0) "last used ${Timeline.ago(m.lastUsedAt, now)}, ${m.uses}×" else "not used since " + FreeTime.day(m.createdAt)
        "- [m:${m.id.take(6)}] ${m.text}（" + listOfNotNull(m.source.takeIf { it.isNotBlank() }, used).joinToString("; ") + "）\n"
    }

    // ---------- 深处 ----------

    private fun deepFile(ctx: Context) = File(ctx.filesDir, "deep_memories.json")

    fun deep(ctx: Context): List<MemoryItem> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(MemoryItem.serializer()), deepFile(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun saveDeep(ctx: Context, list: List<MemoryItem>): Unit = synchronized(lock) {
        runCatching { deepFile(ctx).writeText(json.encodeToString(ListSerializer(MemoryItem.serializer()), list.takeLast(2000))) }
    }

    // ---------- 存档和版本 ----------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private fun archiveFile(ctx: Context) = File(ctx.filesDir, "memories_archive.json")
    private fun versionDir(ctx: Context) = File(ctx.filesDir, "memories_versions").apply { mkdirs() }

    fun archive(ctx: Context): List<ArchivedMemory> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(ArchivedMemory.serializer()), archiveFile(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun addToArchive(ctx: Context, items: List<ArchivedMemory>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            runCatching { archiveFile(ctx).writeText(json.encodeToString(ListSerializer(ArchivedMemory.serializer()), (archive(ctx) + items).takeLast(1000))) }
        }
    }

    /** 改之前存一版（按天，同一天多次只留最后一次之前的那版），最多留 30 天。 */
    fun snapshot(ctx: Context, live: List<MemoryItem>, now: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        val dir = versionDir(ctx)
        val f = File(dir, FreeTime.day(now) + ".json")
        if (!f.exists()) runCatching { f.writeText(json.encodeToString(ListSerializer(MemoryItem.serializer()), live)) }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
    }

    /** 存过的版本（日期，新的在前）。 */
    fun versions(ctx: Context): List<String> = versionDir(ctx).listFiles()?.map { it.name.removeSuffix(".json") }?.sortedDescending().orEmpty()

    fun version(ctx: Context, day: String): List<MemoryItem>? =
        runCatching { json.decodeFromString(ListSerializer(MemoryItem.serializer()), File(versionDir(ctx), "$day.json").readText()) }.getOrNull()
}
