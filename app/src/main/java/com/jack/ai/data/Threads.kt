package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 挂着的一件事。kind：wondering 在琢磨的问题、promise 答应用户的事、unfinished 聊到一半的、looking_forward 在期待的。
 * status：open 挂着、closed 了结了（how 写怎么了结的）、faded 很久没碰，淡出了。
 */
@Serializable
data class OpenThread(
    val id: String = newId(),
    val text: String,
    val kind: String = "wondering",
    val convId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val touchedAt: Long = System.currentTimeMillis(),
    val status: String = "open",
    val closedAt: Long = 0,
    val how: String = "",
)

/**
 * 挂着的事：Kavi 心里挂着的几件事，跨对话、跨自由时间一直带着，直到了结或淡出。
 * 聊天里的由写聊天经历那次请求顺手记下、了结（见 Timeline.summarize）；自由时间里它自己记、自己放下（free_time 工具）。
 * [FADE_DAYS] 天没碰就淡出（不删，淡出也写进经历流）。每轮附加说明里带着最近碰过的 [SHOW] 件。
 */
object Threads {
    const val FADE_DAYS = 14
    const val SHOW = 12
    private const val MAX_OPEN = 20
    val KINDS = setOf("wondering", "promise", "unfinished", "looking_forward")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "threads.json")

    fun load(ctx: Context): List<OpenThread> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(OpenThread.serializer()), file(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<OpenThread>) = synchronized(lock) {
        // 了结和淡出的只留最近的一些（经历流里有记录）
        val keep = list.filter { it.status == "open" } + list.filter { it.status != "open" }.sortedByDescending { it.closedAt }.take(100)
        runCatching { file(ctx).writeText(json.encodeToString(ListSerializer(OpenThread.serializer()), keep)) }
    }

    fun open(list: List<OpenThread>) = list.filter { it.status == "open" }

    /** 记一件新的（意思一样的已经挂着就当碰了一下）。 */
    fun hold(list: List<OpenThread>, text: String, kind: String, convId: String = "", now: Long = System.currentTimeMillis()): List<OpenThread> {
        val t = text.trim().take(200)
        if (t.length < 4) return list
        fun norm(s: String) = s.lowercase().replace(Regex("[\\s，。,.；;：:！!？?「」\"'（）()]"), "")
        val same = list.indexOfFirst { it.status == "open" && norm(it.text) == norm(t) }
        if (same >= 0) return list.mapIndexed { i, x -> if (i == same) x.copy(touchedAt = now) else x }
        val k = kind.takeIf { it in KINDS } ?: "wondering"
        val added = list + OpenThread(text = t, kind = k, convId = convId, createdAt = now, touchedAt = now)
        // 挂太多了：最久没碰的先淡出
        val opens = open(added)
        if (opens.size <= MAX_OPEN) return added
        val drop = opens.sortedBy { it.touchedAt }.take(opens.size - MAX_OPEN).map { it.id }.toSet()
        return added.map { if (it.id in drop) it.copy(status = "faded", closedAt = now) else it }
    }

    private fun find(list: List<OpenThread>, id: String) = if (id.length < 4) -1 else list.indexOfFirst { it.status == "open" && it.id.startsWith(id) }

    fun close(list: List<OpenThread>, id: String, how: String, now: Long = System.currentTimeMillis()): List<OpenThread> {
        val i = find(list, id.removePrefix("t:"))
        return if (i < 0) list else list.mapIndexed { j, x -> if (j == i) x.copy(status = "closed", closedAt = now, how = how.take(120)) else x }
    }

    fun touch(list: List<OpenThread>, id: String, now: Long = System.currentTimeMillis()): List<OpenThread> {
        val i = find(list, id.removePrefix("t:"))
        return if (i < 0) list else list.mapIndexed { j, x -> if (j == i) x.copy(touchedAt = now) else x }
    }

    /** 很久没碰的淡出。返回（新列表，这次淡出的）。 */
    fun fade(list: List<OpenThread>, now: Long = System.currentTimeMillis()): Pair<List<OpenThread>, List<OpenThread>> {
        val gone = list.filter { it.status == "open" && now - it.touchedAt > FADE_DAYS * 86_400_000L }
        if (gone.isEmpty()) return list to emptyList()
        val ids = gone.map { it.id }.toSet()
        return list.map { if (it.id in ids) it.copy(status = "faded", closedAt = now) else it } to gone
    }

    /** 按模型给的 {"open":[{text,kind}], "closed":[{id,how}], "touched":[id]} 改。 */
    fun applyJson(list: List<OpenThread>, o: JsonObject, convId: String, now: Long = System.currentTimeMillis()): List<OpenThread> {
        var out = list
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
        (o["closed"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { out = close(out, it.s("id"), it.s("how"), now) }
        (o["touched"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.forEach { out = touch(out, it, now) }
        (o["open"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { out = hold(out, it.s("text"), it.s("kind"), convId, now) }
        return out
    }

    private fun label(kind: String) = when (kind) {
        "promise" -> "promised {user}"; "unfinished" -> "left unfinished"; "looking_forward" -> "looking forward to"; else -> "wondering"
    }

    /** 给模型看的挂着的事（带编号，好了结）。 */
    fun listing(list: List<OpenThread>, now: Long = System.currentTimeMillis(), max: Int = MAX_OPEN): String =
        open(list).sortedByDescending { it.touchedAt }.take(max).joinToString("\n") { t ->
            "- [t:${t.id.take(6)}] (${label(t.kind)}, since ${Timeline.ago(t.createdAt, now)}) ${t.text}"
        }

    /** 每轮附加说明里的那段：最近碰过的几件。 */
    fun note(list: List<OpenThread>, now: Long = System.currentTimeMillis()): String {
        val s = listing(list, now, SHOW)
        if (s.isBlank()) return ""
        return "【On your mind】(things you're carrying: keep promises; bring the rest up only when it's natural)\n$s\n"
    }
}
