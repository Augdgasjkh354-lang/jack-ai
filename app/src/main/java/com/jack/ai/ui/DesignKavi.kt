package com.jack.ai.ui

import android.content.Context
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.Persona
import com.jack.ai.data.Store
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.endpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 编辑器里的「让 Kavi 改」：选中一块（或整张）说要怎么改，单独请一次模型，只改这一块，
 * 返回新的 HTML 换进画布（一步可撤销）。不开整轮对话、不带聊天记录，快、省。
 */
object DesignKavi {
    /** 编辑器传来的目标：kvEd.target() 的结果。 */
    data class Target(val scope: String, val path: List<Int>, val html: String, val css: String, val slot: String, val kind: String, val text: String, val w: Int, val h: Int)

    data class Result(val html: String, val note: String)

    /** 选中一块时的快捷说法（文字和别的分开）。 */
    val QUICK_TEXT = listOf("更抓人", "更短", "换个说法", "手写体", "更醒目")
    val QUICK_BOX = listOf("更醒目", "换个颜色", "更简洁", "加点装饰")
    val QUICK_PAGE = listOf("更高级", "更简洁", "标题更抓人", "更有活力", "层次更清楚")

    fun parseTarget(raw: String): Target? = runCatching {
        val o = Json.parseToJsonElement(raw).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val path = (o["path"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        Target(s("scope"), path, s("html"), s("css"), s("slot"), s("kind"), s("text"), s("w").toDoubleOrNull()?.toInt() ?: 0, s("h").toDoubleOrNull()?.toInt() ?: 0)
    }.getOrNull()

    private const val RULES = """You edit one piece of a design (an HTML/CSS poster or social-media card) at the user's request. You get the page's CSS, the canvas size, and the HTML of the piece to change (one element, or the whole canvas).
Return only one JSON object: {"html": "the new outerHTML of that same piece", "note": "one short sentence in Chinese saying what you changed"}.

Rules:
- Change only what the request asks for. Keep the same root tag, and keep every data-slot, data-max, data-kind, data-list, data-item, data-icon and data-kv-* attribute exactly as it is.
- Keep the element's position: leave translate, scale, rotate, position, left, top, width and z-index in its style unless the request is about position or size.
- Colors come from the palette variables: var(--bg), var(--fg), var(--primary), var(--secondary), var(--accent), var(--muted), or color-mix() of them. Don't hard-code new hex colors.
- Text must still fit: respect data-max (characters). Shorter is better than longer. Write in the language of the existing text.
- Fonts: only these families, each followed by a system fallback: "Smiley Sans", "Noto Sans SC", "Noto Serif SC", "LXGW WenKai", "ZCOOL KuaiLe", "ZCOOL QingKe HuangYou", "ZCOOL XiaoWei", "Ma Shan Zheng", "Zhi Mang Xing", "Liu Jian Mao Cao", "Long Cang", and for Latin text "Playfair Display", "DM Serif Display", "Bebas Neue", "Oswald", "Montserrat", "Space Grotesk", "Archivo Black", "Caveat" (handwriting: "Ma Shan Zheng" or "LXGW WenKai"), e.g. font-family:"LXGW WenKai","Noto Serif CJK SC",serif.
- Style changes go in the element's own style attribute. No <script>, no event attributes, no external URLs, no <img> (photos are background-image on data-kind="image" elements; don't touch those).
- Good taste: one focal point, strong size contrast, generous spacing, at most three colors, no glow text, no blue-purple gradients, no shadows on everything.
- For the whole canvas: return the full <div class="canvas" ...> with all its children; you may restyle and reword, but keep every slot and the canvas's CSS variables."""

    suspend fun edit(ctx: Context, target: Target, ask: String): Result {
        val store = Store(ctx)
        val input = buildString {
            append("Canvas: ${target.w}×${target.h}px.\n\n")
            append("【Page CSS】\n").append(target.css.take(12_000)).append("\n\n")
            append(if (target.scope == "page") "【Whole canvas HTML】\n" else "【The piece to change】(" + target.kind + (target.slot.takeIf { it.isNotEmpty() }?.let { ", slot $it" } ?: "") + ")\n")
            append(target.html.take(if (target.scope == "page") 40_000 else 8_000)).append("\n\n")
            append("【Request】\n").append(ask.trim())
        }
        val raw = ask(ctx, Persona.fill(RULES, store.loadSettings()), input, if (target.scope == "page") 16_000 else 4_000)
        return parse(raw) ?: error("Kavi 这次没改成，再说一遍试试")
    }

    /** 单独请一次模型（JSON 模式），不带聊天记录。额度用完、没填 Key 时报错。 */
    internal suspend fun ask(ctx: Context, system: String, user: String, maxTokens: Int): String {
        val store = Store(ctx)
        val s = store.loadSettings()
        com.jack.ai.data.Quota.blocked(ctx, s)?.let { error(com.jack.ai.data.Quota.message(it)) }
        val ep = s.endpoint(s.model)?.takeIf { it.apiKey.isNotBlank() } ?: s.backgroundEndpoint()
            ?: error("要用模型：先到设置里填好 DeepSeek Key")
        return DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", user) },
            ),
            maxTokens = maxTokens,
            jsonMode = true,
        )
    }

    /** 点评的一条：贴在哪一块（path 为空是整张）、毛病、怎么改（直接当「让 Kavi 改」的话发）。 */
    data class Advice(val label: Int, val path: List<Int>, val problem: String, val fix: String)

    private const val REVIEW_RULES = """You are a strict design reviewer looking at one design (an HTML/CSS social-media card or poster). You get the automatic checks, a numbered list of the pieces on the canvas (position box x,y,w,h in px, text, font size, color, font), the page CSS and the canvas HTML.
Find the 2 to 5 changes that would improve it most, worst first: problems from the automatic checks come first; then hierarchy (one clear focal point, title at least 2.5x body text), readability (size, contrast), spacing and alignment, clutter, too many colors or fonts, an AI-generated look (blue-purple gradients, glow, shadows everywhere, everything centered).
Each item targets one numbered piece, or 0 for the whole canvas. Don't list things that are fine, and don't ask for photos.
Output only one JSON object: {"items": [{"n": piece number or 0, "problem": "what's wrong, one short sentence in Chinese", "fix": "an instruction in Chinese for an editor who will change only that piece, e.g. 标题字号加大到 120px、加粗"}]}"""

    suspend fun review(ctx: Context, reviewJson: String, target: Target): List<Advice> {
        val o = Json.parseToJsonElement(reviewJson).jsonObject
        val pieces = (o["pieces"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        fun list(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val checks = (list("problems") + list("advice"))
        val input = buildString {
            append("Canvas: ${target.w}×${target.h}px.\n\n【Automatic checks】\n")
            append(checks.joinToString("\n") { "- $it" }.ifEmpty { "(none)" }).append("\n\n【Pieces】\n")
            pieces.forEach { p ->
                append("${p.str("n")}. ${p.str("slot")} (${p.str("kind")}) box=${p["box"]} size=${p.str("size")}px weight=${p.str("weight")} color=${p.str("color")} font=${p.str("font")}")
                p.str("text").takeIf { it.isNotEmpty() }?.let { append(" text=「$it」") }
                append("\n")
            }
            append("\n【Page CSS】\n").append(target.css.take(10_000))
            append("\n\n【Canvas HTML】\n").append(target.html.take(24_000))
        }
        val raw = ask(ctx, REVIEW_RULES, input, 2_000)
        val paths = pieces.associate { p -> p.str("n").toIntOrNull() to (p["path"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() } }
        return parseReview(raw, paths).ifEmpty { error("Kavi 这次没看出问题，再试一次") }
    }

    internal fun parseReview(raw: String, paths: Map<Int?, List<Int>>): List<Advice> = runCatching {
        val o = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
        (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val it = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val n = (it["n"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
            val problem = (it["problem"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val fix = (it["fix"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (problem.isEmpty() || fix.isEmpty()) return@mapNotNull null
            (if (n == 0) emptyList() else paths[n]) to (problem to fix)
        }.filter { it.first != null }.take(5).mapIndexed { i, (path, pf) -> Advice(i + 1, path!!, pf.first, pf.second) }
    }.getOrDefault(emptyList())

    internal fun parse(raw: String): Result? = runCatching {
        val o: JsonObject = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
        val html = (o["html"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (!html.startsWith("<")) return null
        Result(html, (o["note"] as? JsonPrimitive)?.content?.trim().orEmpty())
    }.getOrNull()
}
