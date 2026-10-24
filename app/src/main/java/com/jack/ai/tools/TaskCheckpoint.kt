package com.jack.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 后台任务的断点：每做完一轮工具就把对话存一份。任务半路被系统杀掉（省电、内存不够、App 被划掉），
 * WorkManager 重跑时从断点接着做，不用从头来。做完就删。
 */
object TaskCheckpoint {
    /** 超过这么久的断点不接（多半是上一次的同一个定时任务，情况早变了）。 */
    const val MAX_AGE_MS = 6 * 3600_000L

    const val RESUME_NOTE = "（这个任务上次做到一半被系统打断了，上面是已经做完的步骤。接着把剩下的做完：已经成功的步骤别重做，" +
        "尤其是发送、提交、下单、删除这类；浏览器的网页没了，要用就重新打开。）"

    data class Saved(val messages: List<JsonObject>, val spent: Double)

    fun file(dir: File, id: String) = File(File(dir, "checkpoints"), "${id.replace(Regex("[^\\w-]"), "_")}.json")

    /** 同一个任务、同样的指令才接着做（改过指令就从头来）。 */
    fun keyOf(prompt: String) = prompt.hashCode().toString()

    fun save(f: File, key: String, messages: List<JsonObject>, spent: Double, now: Long = System.currentTimeMillis()) {
        runCatching {
            f.parentFile?.mkdirs()
            val o = buildJsonObject {
                put("key", key); put("at", now); put("spent", spent)
                put("messages", JsonArray(messages))
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    /** 读断点：对不上、太旧、还没做过工具、最后一条是没执行完的工具调用，都不算。 */
    fun load(f: File, key: String, now: Long = System.currentTimeMillis()): Saved? {
        if (!f.isFile) return null
        val o = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content
        if (s("key") != key) return null
        val at = s("at")?.toLongOrNull() ?: return null
        if (now - at > MAX_AGE_MS || at > now) return null
        val msgs = runCatching { (o["messages"] as JsonArray).map { it.jsonObject } }.getOrNull() ?: return null
        if (msgs.none { (it["role"] as? JsonPrimitive)?.content == "tool" }) return null
        return Saved(repair(msgs), s("spent")?.toDoubleOrNull() ?: 0.0)
    }

    fun clear(f: File) { f.delete() }

    /** 上次用 use_skill 打开过的技能名。 */
    fun skillsUsed(msgs: List<JsonObject>): List<String> = msgs.flatMap { m ->
        (m["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
            val fn = (c as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            if ((fn["name"] as? JsonPrimitive)?.content != "use_skill") return@mapNotNull null
            runCatching { (Json.parseToJsonElement((fn["arguments"] as JsonPrimitive).content).jsonObject["name"] as JsonPrimitive).content }.getOrNull()
        }
    }.distinct()

    /** 一轮里好几个工具、做到一半断了：没做的那几个补一条「被打断，没做」，不然接口不收。 */
    internal fun repair(msgs: List<JsonObject>): List<JsonObject> {
        val i = msgs.indexOfLast { (it["role"] as? JsonPrimitive)?.content == "assistant" && it["tool_calls"] != null }
        if (i < 0) return msgs
        val ids = runCatching { (msgs[i]["tool_calls"] as JsonArray).map { (it.jsonObject["id"] as JsonPrimitive).content } }.getOrDefault(emptyList())
        val done = msgs.drop(i + 1).mapNotNull { (it["tool_call_id"] as? JsonPrimitive)?.content }.toSet()
        val missing = ids.filter { it !in done }
        return msgs + missing.map { id -> buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", "（任务在这里被打断了，这一步没做。）") } }
    }
}
