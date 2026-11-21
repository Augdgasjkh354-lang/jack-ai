package com.jack.ai.data.design

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 风格库（assets/design/styles.json）：「审美」技能的底子。
 * 思路参考 baoyu-xhs-images（MIT）：风格、版式、配色是三个独立的旋钮，自由组合；
 * 每种风格把字体、字号、版式、装饰、配色、禁忌都定死，模型照着做，不临场乱配。
 */
object DesignStyles {
    class Style(val id: String, val name: String, val o: JsonObject)
    class Layout(val id: String, val name: String, val use: String, val how: String)
    class Library(val styles: List<Style>, val layouts: List<Layout>)

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: Library? = null

    fun load(ctx: Context): Library = cache ?: runCatching {
        parse(ctx.assets.open("design/styles.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(Library(emptyList(), emptyList())).also { cache = it }

    fun parse(text: String): Library {
        val root = json.parseToJsonElement(text) as JsonObject
        val styles = (root["styles"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Style(o.s("id").ifBlank { return@mapNotNull null }, o.s("name"), o)
        }
        val layouts = (root["layouts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Layout(o.s("id"), o.s("name"), o.s("use"), o.s("how"))
        }
        return Library(styles, layouts)
    }

    /** 不给 id：所有风格一行一个（加上版式）；给 id：这个风格的全部做法。 */
    fun catalog(lib: Library, id: String?): String {
        if (id.isNullOrBlank()) return buildString {
            append("风格（先挑风格，再用 style=风格 id 看它的字体、配色、版式和禁忌）：\n")
            lib.styles.forEach { s -> append("- ${s.id}｜${s.name}｜${s.o.s("mood")}｜适合：${s.o.arr("fits").joinToString("、")}\n") }
            append("\n版式（和风格自由组合）：\n")
            lib.layouts.forEach { l -> append("- ${l.id}｜${l.name}｜${l.use}：${l.how}\n") }
        }.trimEnd()
        val s = lib.styles.firstOrNull { it.id == id.trim() || it.name == id.trim() }
            ?: return "没有「$id」这个风格。有：" + lib.styles.joinToString("、") { "${it.id}（${it.name}）" }
        val o = s.o
        val fonts = o["fonts"] as? JsonObject
        return buildString {
            append("风格：${s.name}（${s.id}）— ${o.s("mood")}\n")
            append("适合：${o.arr("fits").joinToString("、")}\n")
            if (fonts != null) {
                append("字体：标题 \"${fonts.s("title")}\"，正文 \"${fonts.s("body")}\"")
                fonts.s("latin").takeIf { it.isNotBlank() }?.let { append("，英文数字 \"$it\"") }
                append("\n")
            }
            append("字号字重：${o.s("type")}\n")
            append("版式：${o.s("layout")}\n")
            append("装饰：${o.s("decor")}\n")
            append("配色（colors 直接照抄一组；要改就只微调明暗，别换色相）：\n")
            (o["palettes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { p ->
                append("- ${p.s("name")}：" + listOf("bg", "fg", "primary", "secondary", "accent", "muted").joinToString(" ") { "$it=${p.s(it)}" } + "\n")
            }
            append("禁忌：${o.s("avoid")}")
            o.arr("templates").takeIf { it.isNotEmpty() }?.let { append("\n这个风格的模板：${it.joinToString("、")}") }
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
}
