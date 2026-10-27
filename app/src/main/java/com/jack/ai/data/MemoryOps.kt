package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 做梦改记忆不再整份重写，而是提交一张修改单：一条条新增 / 修改 / 作废，各带理由和出处。
 * 代码来执行：改前先存一版（能回退），作废的挪进存档（不删），一次改动太多就整次不做、第二天重新整理。
 */
object MemoryOps {
    data class Op(val op: String, val id: String = "", val text: String = "", val why: String = "", val source: String = "")
    data class Result(val live: List<MemoryItem>, val archived: List<ArchivedMemory>, val added: Int, val updated: Int, val invalidated: Int, val skipped: Int)

    /** 一次最多动多少条已有的（修改 + 作废）：超过就整次不做。 */
    fun limit(liveSize: Int) = maxOf(5, (liveSize * 0.3).toInt())

    fun parse(arr: JsonArray?): List<Op> = arr.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
        val op = s("op").lowercase().takeIf { it in setOf("add", "update", "invalidate") } ?: return@mapNotNull null
        Op(op, s("id").removePrefix("m:"), s("text"), s("why"), s("source"))
    }

    /** 这张修改单动到的已有记忆是不是太多了。 */
    fun tooMany(liveSize: Int, ops: List<Op>): Boolean = ops.count { it.op != "add" } > limit(liveSize)

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s，。,.；;：:！!？?「」\"'（）()]"), "")

    /** 在现在的记忆上执行（期间用户删掉、改过的照现在的算；找不到的编号跳过）。 */
    fun apply(live: List<MemoryItem>, ops: List<Op>, now: Long = System.currentTimeMillis()): Result {
        val list = live.toMutableList()
        val archived = mutableListOf<ArchivedMemory>()
        var added = 0; var updated = 0; var invalidated = 0; var skipped = 0
        fun find(id: String) = if (id.length < 4) -1 else list.indexOfFirst { it.id.startsWith(id) }
        for (op in ops) when (op.op) {
            "add" -> {
                if (op.text.length < 4 || list.any { norm(it.text) == norm(op.text) }) { skipped++; continue }
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
                if (i < 0) { skipped++; continue }
                archived += ArchivedMemory(list.removeAt(i), now, op.why, op.source); invalidated++
            }
        }
        return Result(list, archived, added, updated, invalidated, skipped)
    }

    /** 给模型看的记忆：带短编号和出处。 */
    fun listing(live: List<MemoryItem>): String = if (live.isEmpty()) "(none)\n" else live.joinToString("") { m ->
        "- [m:${m.id.take(6)}] ${m.text}" + (if (m.source.isNotBlank()) "（${m.source}）" else "") + "\n"
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
