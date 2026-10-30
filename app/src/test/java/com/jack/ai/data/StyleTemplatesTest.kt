package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 我的模板：补设置不覆盖这次写的、默认模板只在新生成时记进底稿、同类只有一个默认。 */
class StyleTemplatesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val company = StyleTemplates.Template("公司汇报", "ppt", mapOf("transition" to JsonPrimitive("morph"), "animation" to JsonPrimitive("rise")),
        colors = StyleTemplates.Colors.of(PptDeck.theme("teal")), isDefault = true)
    private val report = StyleTemplates.Template("正式报告", "word", mapOf("cover" to obj("""{"author":"Jack","organization":"产品部"}"""),
        "toc" to JsonPrimitive(true), "font_style" to JsonPrimitive("classic")))
    private val list = listOf(company, report)

    @Test
    fun templateFillsOnlyWhatWasNotAsked() {
        val (args, colors, t) = StyleTemplates.apply(list, "create_ppt", obj("""{"template":"公司","transition":"fade","slides":[]}"""))
        assertEquals("公司汇报", t!!.name)
        assertEquals("fade", args["transition"]!!.jsonPrimitive.content)   // 这次写了的优先
        assertEquals("rise", args["animation"]!!.jsonPrimitive.content)   // 没写的从模板补
        assertEquals("0F766E", colors!!.primary)
        // 这次自己指定了配色，就不用模板里的整套颜色
        assertNull(StyleTemplates.apply(list, "create_ppt", obj("""{"template":"公司汇报","theme":"red"}""")).second)
        // Word 封面：这次的标题加上模板里的作者、单位
        val (w, _, _) = StyleTemplates.apply(list, "create_word", obj("""{"template":"正式报告","cover":{"title":"年度总结"},"markdown":"# x"}"""))
        val cover = w["cover"]!!.jsonObject
        assertEquals("年度总结", cover["title"]!!.jsonPrimitive.content)
        assertEquals("产品部", cover["organization"]!!.jsonPrimitive.content)
        assertEquals("classic", w["font_style"]!!.jsonPrimitive.content)
    }

    @Test
    fun defaultIsRecordedOnlyForNewFiles() {
        // 新生成：记下默认模板的名字
        val fresh = StyleTemplates.withDefault(list, "create_ppt", obj("""{"slides":[]}"""))
        assertEquals("公司汇报", fresh["template"]!!.jsonPrimitive.content)
        // 写了 none 或别的模板：不动
        assertEquals("none", StyleTemplates.withDefault(list, "create_ppt", obj("""{"template":"none"}"""))["template"]!!.jsonPrimitive.content)
        // Word 没有默认模板：不加
        assertFalse("template" in StyleTemplates.withDefault(list, "create_word", obj("""{"markdown":"# x"}""")))
        // 重新生成老文件（底稿里没有 template）：不会套上后来设的默认
        val (old, colors, t) = StyleTemplates.apply(list, "create_ppt", obj("""{"slides":[]}"""))
        assertNull(t); assertNull(colors); assertFalse("template" in old)
        assertNull(StyleTemplates.apply(list, "create_ppt", obj("""{"template":"none"}""")).third)
    }

    @Test
    fun onlyOneDefaultPerKindAndSameNameReplaces() {
        val other = StyleTemplates.Template("活泼", "ppt", mapOf("theme" to JsonPrimitive("orange")), isDefault = true)
        val next = StyleTemplates.put(list, other)
        assertEquals(listOf("活泼"), next.filter { it.kind == "ppt" && it.isDefault }.map { it.name })
        assertEquals(3, next.size)
        assertEquals(3, StyleTemplates.put(next, other.copy(settings = emptyMap())).size)
        assertNotNull(StyleTemplates.find(next, "word", "报告"))
        assertTrue(StyleTemplates.describe(next).contains("「公司汇报」PPT"))
        // 存的整套颜色还原成主题
        assertEquals(PptDeck.theme("teal"), company.colors!!.theme())
        // 能存成 JSON 再读回来
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(StyleTemplates.Template.serializer(), json.encodeToString(StyleTemplates.Template.serializer(), report))
        assertEquals(report, back)
        assertTrue((back.settings["cover"] as JsonObject).containsKey("author"))
    }
}
