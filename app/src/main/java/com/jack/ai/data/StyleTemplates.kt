package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 我的模板：用户满意的风格存下来，以后「用我的模板」一句话就行；设成默认的话不说也照它来。
 * PPT 存配色（也可以是从用户的 PPT 里读出来的整套颜色和字体）、转场、动画；Word 存配色、封面信息、目录、编号、缩进、字体、页眉；Excel 存配色。
 */
object StyleTemplates {
    /** 每种文件能存进模板的设置（和生成工具的参数同名）。 */
    val KEYS = mapOf(
        "ppt" to setOf("theme", "transition", "animation"),
        "word" to setOf("theme", "cover", "toc", "header", "page_numbers", "heading_numbers", "indent", "font_style"),
        "excel" to setOf("theme"),
    )

    fun kindOf(tool: String) = when (tool) { "create_ppt" -> "ppt"; "create_word" -> "word"; "create_excel" -> "excel"; else -> null }

    @Serializable
    data class Colors(
        val primary: String, val accent: String, val bg: String, val title: String, val text: String,
        val soft: String, val card: String, val muted: String, val dark: Boolean,
        val latin: String, val ea: String, val headLatin: String, val headEa: String,
    ) {
        fun theme() = PptDeck.Theme(primary, accent, bg, title, text, soft, card, muted, dark, latin, ea, headLatin, headEa)

        companion object {
            fun of(t: PptDeck.Theme) = Colors(t.primary, t.accent, t.bg, t.title, t.text, t.soft, t.card, t.muted, t.dark, t.latin, t.ea, t.headLatin, t.headEa)
        }
    }

    @Serializable
    data class Template(
        val name: String,
        val kind: String,
        val settings: Map<String, JsonElement> = emptyMap(),
        /** 从用户的 PPT 里读出来的整套配色和字体；有它就不看 settings 里的 theme。 */
        val colors: Colors? = null,
        val isDefault: Boolean = false,
        /** 从哪来的（文件名），列出来给用户看。 */
        val source: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "style_templates.json")

    fun load(ctx: Context): List<Template> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(Template.serializer()), file(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<Template>) = synchronized(lock) {
        file(ctx).writeText(json.encodeToString(ListSerializer(Template.serializer()), list))
    }

    /** 加一个（同名同类的换掉）；设成默认的话同类别的取消默认。 */
    fun put(list: List<Template>, t: Template): List<Template> =
        list.filterNot { it.kind == t.kind && it.name == t.name }
            .map { if (t.isDefault && it.kind == t.kind) it.copy(isDefault = false) else it } + t

    /** 按名字找：先找一模一样的，再找包含的。 */
    fun find(list: List<Template>, kind: String, name: String): Template? {
        val n = name.trim()
        val same = list.filter { it.kind == kind }
        return same.firstOrNull { it.name == n } ?: same.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
    }

    /**
     * 生成时用模板：参数里写了 template 用那个，没写就用这一类的默认模板（写 "none" 就都不用）。
     * 参数里自己写了的设置不覆盖（这次的要求优先），没写的从模板里补；用了模板就把名字记进参数，以后改这份文件还照它来。
     * 返回补好的参数和要用的整套配色（模板里存的是从 PPT 读出来的颜色时）。
     */
    fun apply(list: List<Template>, tool: String, args: JsonObject): Triple<JsonObject, Colors?, Template?> {
        val kind = kindOf(tool) ?: return Triple(args, null, null)
        val asked = (args["template"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (asked.isEmpty() || asked == "none") return Triple(args, null, null)
        val t = find(list, kind, asked) ?: return Triple(args, null, null)
        val out = args.toMutableMap()
        t.settings.forEach { (k, v) ->
            if (k !in KEYS.getValue(kind)) return@forEach
            val mine = out[k]
            // Word 封面：这次写了一部分（比如标题），作者、单位这些从模板补
            if (k == "cover" && mine is JsonObject && v is JsonObject) out[k] = JsonObject(v + mine)
            else if (mine == null) out[k] = v
        }
        out["template"] = JsonPrimitive(t.name)
        // 这次自己指定了配色或要照某个 PPT 做，就不用模板里的整套颜色
        val colors = t.colors.takeIf { "theme" !in args && "style_from" !in args }
        return Triple(JsonObject(out), colors, t)
    }

    /**
     * 新生成一份时：没写 template 就把这一类的默认模板名字记进参数（存进底稿），以后改这份文件还照它来；
     * 老文件重新生成时不会因为后来设了默认模板就变样。
     */
    fun withDefault(list: List<Template>, tool: String, args: JsonObject): JsonObject {
        val kind = kindOf(tool) ?: return args
        if ("template" in args) return args
        val d = list.firstOrNull { it.kind == kind && it.isDefault } ?: return args
        return JsonObject(args + ("template" to JsonPrimitive(d.name)))
    }

    /** 列给模型看：名字、类别、是不是默认、存了什么。 */
    fun describe(list: List<Template>): String = if (list.isEmpty()) "还没有模板。" else list.sortedBy { it.kind }.joinToString("\n") { t ->
        val kind = when (t.kind) { "ppt" -> "PPT"; "word" -> "Word"; else -> "Excel" }
        "- 「${t.name}」$kind" + (if (t.isDefault) "（默认）" else "") + "：" +
            (listOfNotNull(t.colors?.let { "配色取自${t.source.ifBlank { "用户的 PPT" }}（主色 #${it.primary}，字体 ${it.headEa}/${it.ea}）" }) +
                t.settings.map { (k, v) -> "$k=" + (if (v is JsonPrimitive) v.content else v.toString()).take(60) }).joinToString("，").ifEmpty { "没存设置" }
    }
}
