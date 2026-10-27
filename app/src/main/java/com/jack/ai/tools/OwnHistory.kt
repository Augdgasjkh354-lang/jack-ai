package com.jack.ai.tools

import com.jack.ai.data.DreamArchive
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.FreeConversation
import com.jack.ai.data.FreeTime
import com.jack.ai.data.OwnMemory
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 只读自己的历史原文，按编号和偏移分段；记忆可以自己调整到常记或深处。 */
internal suspend fun ToolSession.ownHistory(args: JsonObject): String = runTool("回看自己的经历") {
    val action = args["action"].text().ifBlank { "list" }
    val kind = args["kind"].text().ifBlank { "all" }
    val id = args["id"].text().trim()
    val query = args["query"].text().trim()
    val offset = args["offset"].text().toIntOrNull()?.coerceAtLeast(0) ?: 0
    val text = withContext(Dispatchers.IO) {
        if (action == "move") {
            val target = args["layer"].text()
            if (target !in setOf("hot", "deep")) "请指定 layer 为 hot 或 deep。"
            else if (OwnMemory.move(app, id, target == "deep")) "已调整这条自己的记忆。" else "没有找到唯一的记忆编号。"
        } else {
            val dreams = (DreamArchive.load(app) + host.store.loadDreams()).associateBy { it.id }.values.toList()
            val dir = File(com.jack.ai.data.Workspace.dir(app, com.jack.ai.data.Reader.CONV_ID), ".src")
            val files = dir.listFiles().orEmpty().filter { f -> f.isFile && f.name.endsWith(".json") && (f.name.startsWith("free_session_") || f.name.startsWith("free_day_") || f.name.startsWith("free_archive_") || f.name == "free_conversation.json") }
            val memories = OwnMemory.load(app)
            val entries = Timeline.load(app)
            if (action == "read") {
                val dream = dreams.firstOrNull { it.id == id }
                val memory = memories.firstOrNull { it.id == id || (id.length >= 6 && it.id.startsWith(id)) }
                val raw = when {
                    dream != null && kind in setOf("all", "dream") -> dreamText(dream)
                    memory != null && kind in setOf("all", "memory") -> "${FreeTime.day(memory.at)} ${memory.kind}：${memory.text}（来源 ${memory.source}）"
                    kind in setOf("all", "free") -> FreeConversation.source(app, id)?.readText()
                    else -> null
                }
                if (raw == null) "没有找到这条记录。" else {
                    val part = raw.drop(offset).take(12_000)
                    "共 ${raw.length} 字符，从 $offset 开始：\n$part" + if (offset + part.length < raw.length) "\n还有内容，用 offset=${offset + part.length} 接着读。" else ""
                }
            } else {
                val lines = mutableListOf<Triple<Long, String, String>>()
                if (kind in setOf("all", "dream")) dreams.forEach { d -> lines += Triple(d.createdAt, "梦 [${d.id}] ${FreeTime.day(d.createdAt)}" + if (d.merged > 0) "（合并记录）" else "", dreamText(d)) }
                if (kind in setOf("all", "free")) files.forEach { f -> lines += Triple(OwnMemory.sourceTime(f), "自由时间 [${f.name}] ${FreeTime.day(OwnMemory.sourceTime(f))}", if (query.isBlank()) "" else f.readText()) }
                if (kind in setOf("all", "memory")) memories.forEach { m -> lines += Triple(m.at, "记忆 [${m.id}] ${FreeTime.day(m.at)} ${if (m.deep) "深处" else "常记"}：${m.text}", m.text) }
                if (kind in setOf("all", "experience")) entries.forEach { e -> lines += Triple(e.at, "经历 ${FreeTime.day(e.at)} ${e.place}：${e.text}", e.text) }
                val start = args["start_date"].text().takeIf { it.isNotBlank() }
                val end = args["end_date"].text().takeIf { it.isNotBlank() }
                val filtered = lines.filter {
                    val day = FreeTime.day(it.first)
                    (start == null || day >= start) && (end == null || day <= end) &&
                        (query.isBlank() || (it.second + it.third).contains(query, ignoreCase = true))
                }.sortedByDescending { it.first }
                val page = filtered.drop(offset).take(20)
                if (page.isEmpty()) "没有找到记录。" else page.joinToString("\n") { it.second } + if (offset + page.size < filtered.size) "\n还有 ${filtered.size - offset - page.size} 条，用 offset=${offset + page.size} 继续。" else ""
            }
        }
    }
    text to SearchRecord("回看自己的经历")
}

private fun dreamText(d: DreamEntry): String = buildString {
    append("【${FreeTime.day(d.createdAt)}")
    if (d.merged > 0) append("，合并了 ${d.merged} 篇，自 ${FreeTime.day(d.from)} 起")
    append("】")
    listOf("梦" to d.dream, "日记" to d.journal, "摘要" to d.summary, "记忆变化" to d.changes, "醒来想说" to d.note)
        .filter { it.second.isNotBlank() }.forEach { (title, content) -> append("\n\n$title：\n$content") }
}
