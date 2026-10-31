package com.jack.ai.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File

/** 自由时间的对话原文。压缩前另存一份，换手机时随工作台一起备份。 */
object FreeConversation {
    const val WARN = 800_000
    const val COMPACT = 950_000
    private val json = Json { ignoreUnknownKeys = true }
    private fun dir(ctx: Context) = File(Workspace.dir(ctx, Reader.CONV_ID), ".src").apply { mkdirs() }
    private fun file(ctx: Context) = AtomicFile(File(dir(ctx), "free_conversation.json"))

    data class State(val messages: List<JsonObject> = emptyList(), val lastTokens: Int = 0, val lastEstimate: Int = 0, val warned: Boolean = false, val next: String = "", val spent: Double = 0.0, val day: String = "", val borrowed: Double = 0.0, val conversationDay: String = "")

    fun load(ctx: Context): State {
        val f = file(ctx)
        val text = try { String(f.readFully()) } catch (_: java.io.FileNotFoundException) { return State() }
        // 坏了先把原文归档再从头开始：不归档的话，下一次 save 会直接覆盖掉这个坏文件，原文就再也找不回来了。
        // 归档的不挂待整理标记（坏文件整理不出来，只留着给人看）
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            runCatching { File(dir(ctx), "free_corrupt_${System.currentTimeMillis()}.json").writeText(text) }
            return State()
        }
        return State((o["messages"] as? JsonArray).orEmpty().map { it.jsonObject },
            (o["lastTokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            (o["lastEstimate"] as? JsonPrimitive)?.intOrNull ?: 0,
            (o["warned"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["next"] as? JsonPrimitive)?.content.orEmpty(),
            (o["spent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            (o["day"] as? JsonPrimitive)?.content.orEmpty(),
            (o["borrowed"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            (o["conversationDay"] as? JsonPrimitive)?.content.orEmpty())
    }

    fun save(ctx: Context, state: State) {
        val data = buildJsonObject {
            put("messages", JsonArray(state.messages)); put("lastTokens", state.lastTokens)
            put("lastEstimate", state.lastEstimate); put("warned", state.warned); put("next", state.next)
            put("spent", state.spent); put("day", state.day); put("borrowed", state.borrowed)
            put("conversationDay", state.conversationDay)
        }.toString()
        val f = file(ctx); val out = f.startWrite()
        try { out.write(data.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    /** 每个自然日使用一段独立对话；换日时先保留前一天的完整记录。 */
    fun forDay(ctx: Context, today: String): State {
        val state = load(ctx)
        val previousDay = state.conversationDay.ifBlank {
            state.day.ifBlank {
                file(ctx).baseFile.lastModified().takeIf { it > 0 }?.let(FreeTime::day).orEmpty()
            }
        }
        if (state.messages.isEmpty() || previousDay.isBlank() || previousDay == today) {
            return state.copy(conversationDay = today)
        }
        val archive = AtomicFile(File(dir(ctx), "free_day_${previousDay}_${java.util.UUID.randomUUID()}.json"))
        val out = archive.startWrite()
        try { out.write(file(ctx).readFully()); archive.finishWrite(out) }
        catch (e: Exception) { archive.failWrite(out); throw e }
        File(dir(ctx), "pending_${archive.baseFile.name}").writeText("0")
        return State(conversationDay = today).also { save(ctx, it) }
    }

    /** 每次自由时间留原文；待整理标记会在记忆成功生成后删除。 */
    fun snapshot(ctx: Context, state: State): String {
        val name = "free_session_${state.conversationDay.ifBlank { FreeTime.day(System.currentTimeMillis()) }}_${java.util.UUID.randomUUID()}.json"
        val archive = AtomicFile(File(dir(ctx), name)); val out = archive.startWrite()
        try { out.write(JsonArray(state.messages).toString().toByteArray()); archive.finishWrite(out) }
        catch (e: Exception) { archive.failWrite(out); throw e }
        File(dir(ctx), "pending_$name").writeText("0")
        return name
    }
    /** 迁移时为旧日记录排一次整理队列，原文始终留着。 */
    fun queueHistorical(ctx: Context) {
        val folder = dir(ctx)
        val flag = File(folder, "own_history_indexed.json")
        if (flag.exists()) return
        folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") && (it.name.startsWith("free_day_") || it.name.startsWith("free_archive_")) }.forEach { f ->
            File(folder, "pending_${f.name}").writeText("0")
        }
        flag.writeText("{}")
    }
    fun pending(ctx: Context): List<String> = dir(ctx).listFiles().orEmpty()
        .filter { it.name.startsWith("pending_free_") }.sortedBy { it.lastModified() }.map { it.name.removePrefix("pending_") }
    fun pendingOffset(ctx: Context, source: String): Int =
        runCatching { File(dir(ctx), "pending_$source").readText().toInt().coerceAtLeast(0) }.getOrDefault(0)
    fun advancePending(ctx: Context, source: String, next: Int?) {
        val marker = File(dir(ctx), "pending_$source")
        if (next == null) marker.delete() else {
            val f = AtomicFile(marker); val out = f.startWrite()
            try { out.write(next.toString().toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
        }
    }
    fun source(ctx: Context, name: String): File? =
        File(dir(ctx), name).takeIf { name == it.name && name.startsWith("free_") && name.endsWith(".json") && it.isFile }

    fun estimate(messages: List<JsonObject>, tools: JsonArray?): Int =
        Compression.estimateTokens(JsonArray(messages).toString()) + (tools?.let { Compression.estimateTokens(it.toString()) } ?: 0)

    fun current(estimate: Int, previousEstimate: Int, actual: Int): Int =
        if (actual > 0 && previousEstimate > 0) actual + (estimate - previousEstimate).coerceAtLeast(0) else estimate

    /** 在完整工具调用之间切开，最近一段原文继续留在对话里。 */
    internal fun cutIndex(messages: List<JsonObject>): Int? {
        var tokens = 0
        for (i in messages.indices.reversed()) {
            tokens += Compression.estimateTokens(messages[i].toString())
            if (i > 1 && tokens >= 150_000 && (messages[i]["role"] as? JsonPrimitive)?.content in setOf("user", "assistant")) return i
        }
        return null
    }

    suspend fun compact(ctx: Context, messages: MutableList<JsonObject>, summarize: suspend (List<JsonObject>) -> String) {
        val cut = cutIndex(messages) ?: throw IllegalStateException("自由时间的对话太长，暂时找不到安全的压缩位置")
        val old = messages.subList(1, cut).toList()
        val request = listOf(
            buildJsonObject { put("role", "system"); put("content", "请把这段自由时间的对话整理成供 Kavi 自己继续使用的记忆。保留它的原话、想法如何变化、未完成的片段、做过的事、工具结果中的重要细节和文件名。不要虚构，不要把好奇或未完成的事变成必须执行的计划。") },
            buildJsonObject { put("role", "user"); put("content", JsonArray(old).toString()) },
        )
        val summary = summarize(request)
        // 先归档再替换；任何一步失败都保留原对话。
        val archive = File(dir(ctx), "free_archive_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.json")
        archive.writeText(JsonArray(old).toString())
        File(dir(ctx), "pending_${archive.name}").writeText("0")
        val rest = messages.drop(cut)
        val system = messages.first()
        messages.clear(); messages += system
        messages += buildJsonObject { put("role", "user"); put("content", "【自由时间的较早对话整理】\n$summary") }
        messages += rest
    }
}
