package com.jack.ai.chat

import com.jack.ai.api.ApiException
import kotlinx.serialization.json.*

/** 思路来自 deepseek-ai/deepseek-harness packages/compaction/compaction-image-offload/，MIT。请求被图片预算拒绝后只补救一次。 */
object ImageOffload {
    fun rejected(e: ApiException, messages: List<JsonObject>? = null): Boolean {
        val text = (e.message.orEmpty() + " " + e.serviceCode).lowercase()
        val aboutImages = Regex("image|图片|图像").containsMatchIn(text)
        // 413（请求太大）不一定是图片的事：也可能是工具结果、长文。只有明说是图片、或图片占了请求的一大半时才删旧图，
        // 不然删了图照样太大，还白白丢了图片
        if (e.code == 413) return aboutImages || messages == null || imageShare(messages) >= 0.5
        return (e.code in setOf(0, 400, 422)) && aboutImages &&
            Regex("too.?many|too.?large|exceed|maximum|limit|size|budget|过多|太多|过大|太大|超出|超过|上限").containsMatchIn(text)
    }

    /** 请求里内联图片（data URL）占了多大比例（按字数）。 */
    internal fun imageShare(messages: List<JsonObject>): Double {
        var images = 0L
        var all = 0L
        for (m in messages) {
            val c = m["content"]
            if (c is JsonArray) c.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                val n = p.toString().length.toLong()
                all += n
                if (p["type"]?.toString()?.contains("image") == true) images += n
            } else all += c?.toString()?.length ?: 0
            m["tool_calls"]?.let { all += it.toString().length }
        }
        return if (all == 0L) 0.0 else images.toDouble() / all
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/compaction/compaction-image-offload/，MIT。按消息顺序省略旧图，优先保留最新三张与 6MB 内联预算。 */
    fun reduce(messages: MutableList<JsonObject>, keep: Int = 3, maxChars: Int = 6_000_000): Boolean {
        val images = messages.flatMapIndexed { mi, m ->
            (m["content"] as? JsonArray).orEmpty().mapIndexedNotNull { pi, part ->
                val p = part as? JsonObject
                if ((p?.get("type") as? JsonPrimitive)?.content == "image_url") Triple(mi, pi, p.toString().length) else null
            }
        }
        if (images.size <= 1) return false
        var count = images.size
        var bytes = images.sumOf { it.third.toLong() }
        val remove = mutableSetOf<Pair<Int, Int>>()
        for ((mi, pi, size) in images.dropLast(1)) {
            // 服务未报出确切预算时，至少省略一张最旧图片，给一次有效重试。
            if (count <= keep && bytes <= maxChars && remove.isNotEmpty()) break
            remove += mi to pi; count--; bytes -= size
        }
        messages.indices.forEach { mi ->
            val m = messages[mi]
            val parts = m["content"] as? JsonArray ?: return@forEach
            messages[mi] = JsonObject(m + ("content" to JsonArray(parts.mapIndexed { pi, part ->
                if ((mi to pi) in remove) buildJsonObject { put("type", "text"); put("text", "[这里原来有一张截图，已省略]") } else part
            })))
        }
        return remove.isNotEmpty()
    }
}
