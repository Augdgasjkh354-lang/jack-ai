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

    data class State(val messages: List<JsonObject> = emptyList(), val lastTokens: Int = 0, val lastEstimate: Int = 0, val warned: Boolean = false, val next: String = "", val spent: Double = 0.0, val day: String = "", val borrowed: Double = 0.0)

    fun load(ctx: Context): State {
        val f = file(ctx)
        val text = try { String(f.readFully()) } catch (_: java.io.FileNotFoundException) { return State() }
        val o = json.parseToJsonElement(text).jsonObject
        return State((o["messages"] as? JsonArray).orEmpty().map { it.jsonObject },
            (o["lastTokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            (o["lastEstimate"] as? JsonPrimitive)?.intOrNull ?: 0,
            (o["warned"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["next"] as? JsonPrimitive)?.content.orEmpty(),
            (o["spent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            (o["day"] as? JsonPrimitive)?.content.orEmpty(),
            (o["borrowed"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
    }

    fun save(ctx: Context, state: State) {
        val data = buildJsonObject {
            put("messages", JsonArray(state.messages)); put("lastTokens", state.lastTokens)
            put("lastEstimate", state.lastEstimate); put("warned", state.warned); put("next", state.next)
            put("spent", state.spent); put("day", state.day); put("borrowed", state.borrowed)
        }.toString()
        val f = file(ctx); val out = f.startWrite()
        try { out.write(data.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

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
        val rest = messages.drop(cut)
        val system = messages.first()
        messages.clear(); messages += system
        messages += buildJsonObject { put("role", "user"); put("content", "【自由时间的较早对话整理】\n$summary") }
        messages += rest
    }
}
