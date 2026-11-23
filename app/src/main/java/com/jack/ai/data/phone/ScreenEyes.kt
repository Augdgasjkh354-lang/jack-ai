package com.jack.ai.data.phone

import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.Endpoint
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.Settings
import com.jack.ai.data.customModelKey
import com.jack.ai.data.endpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 操作手机时的「眼睛」（借鉴 Operit 的 AutoGLM 看截图操作）：屏幕上读不出字（图标、游戏、画出来的按钮），
 * 当前模型又看不了图时，交给一个能看图的模型把截图认成带坐标的清单，主模型照样按坐标 tap。
 * 只认、不决定做什么：要点哪个还是主模型说了算，付款、发送这些关照旧拦着。
 */
object ScreenEyes {
    /** 看到的一项：说的是什么、哪类、中心点（截图上的像素）。 */
    data class Spot(val label: String, val kind: String, val x: Int, val y: Int)

    /** 用哪个模型看：有 DeepSeek Key 用 Flash（便宜、能看图），否则用配好的第一个能看图的模型；都没有就是 null。 */
    fun eyes(settings: Settings, except: String? = null): Endpoint? {
        if (settings.apiKey.isNotBlank() && except != MODEL_FLASH) settings.endpoint(MODEL_FLASH)?.takeIf { it.vision }?.let { return it }
        return settings.providers.asSequence().filter { it.apiKey.isNotBlank() || it.baseUrl == com.jack.ai.data.CHATGPT_PROVIDER_URL }
            .flatMap { p -> p.models.filter { it.vision }.map { customModelKey(p.id, it.id) } }
            .filter { it != except }.mapNotNull { settings.endpoint(it) }.firstOrNull()
    }

    private val PROMPT = """This is a phone screenshot. List what a person could tap or read on it: buttons, icons (say what the icon means, e.g. "搜索图标", "返回箭头", "设置齿轮"), tabs, input boxes, list items, text, game controls, pop-up buttons.
Reply with JSON only: {"items":[{"label":"...","type":"button|icon|input|tab|item|text|other","x":0,"y":0}]}
x and y are the center of the element on a 0-1000 scale (0,0 top-left, 1000,1000 bottom-right). Top to bottom, at most 40 items. Write labels in the language shown on screen; keep them short."""

    /** 解析模型的回答，坐标换成截图像素（[w]×[h]）。 */
    internal fun parse(raw: String, w: Int, h: Int): List<Spot> {
        val o = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull() ?: return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val it = e as? JsonObject ?: return@mapNotNull null
            val label = (it["label"] as? JsonPrimitive)?.content?.trim().orEmpty().take(40)
            val x = (it["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (it["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (label.isEmpty() || x !in 0.0..1000.0 || y !in 0.0..1000.0) return@mapNotNull null
            Spot(label, (it["type"] as? JsonPrimitive)?.content.orEmpty(), (x / 1000 * w).toInt(), (y / 1000 * h).toInt())
        }.distinctBy { it.label to (it.x / 20 to it.y / 20) }.take(40)
    }

    /** 看一张截图（data: URI，[w]×[h] 像素）。看不了、出错时返回空。 */
    suspend fun look(client: DeepSeekClient, ep: Endpoint, image: String, w: Int, h: Int): List<Spot> {
        val raw = runCatching {
            client.complete(ep, listOf(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", PROMPT) })
                    add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", image) }) })
                })
            }), maxTokens = 3000, jsonMode = true)
        }.getOrNull() ?: return emptyList()
        return parse(raw, w, h)
    }

    private val KIND = mapOf("button" to "按钮", "icon" to "图标", "input" to "输入框", "tab" to "标签", "item" to "列表项", "text" to "文字")

    /** 给主模型看的清单。 */
    fun format(spots: List<Spot>, model: String): String = if (spots.isEmpty()) "" else
        "【看图认出的】（$model 看截图认的，坐标是截图上的像素，按 tap 的 x、y 点；认得不一定准，点完看结果）\n" +
            spots.joinToString("\n") { "- ${KIND[it.kind] ?: "元素"}「${it.label}」(${it.x}, ${it.y})" } + "\n"
}
