package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTemplatesTest {
    private val index = """[{"id":"xhs-knowledge-fresh","name":"知识点卡片·清新","category":"xhs","size":[1080,1440],"style":"清新",
        "description":"大标题加要点","slots":[{"name":"title","label":"标题","max":16}],
        "lists":[{"name":"points","label":"要点","min":3,"max":6,"slots":[{"name":"text","label":"要点","max":28}]}],
        "images":[{"name":"photo","label":"配图","optional":true}],"colors":{"bg":"#F6FBF8","primary":"#2F9E6E"}},
        {"id":"long-guide","name":"攻略长图","category":"long","size":[1080,0]},{"id":"Bad Id","name":"x"}]"""

    @Test
    fun catalogListsWhatCanBeFilled() {
        val list = DesignTemplates.parseIndex(index)
        assertEquals(listOf("xhs-knowledge-fresh", "long-guide"), list.map { it.id })
        assertEquals(0, list[1].h)
        val text = DesignTemplates.catalog(list, "xhs")
        assertTrue(text, "title（标题 ≤16 字）" in text && "points（要点 3~6 项" in text && "photo（配图，可不填）" in text && "primary=#2F9E6E" in text)
        assertFalse("long-guide" in text)
        assertTrue("1080×长" in DesignTemplates.catalog(list, null))
        assertTrue("还没有模板" in DesignTemplates.catalog(list, "card"))
    }

    @Test
    fun sizesAndColorsAreChecked() {
        assertEquals(1080 to 1440, DesignTemplates.size("xhs"))
        assertEquals(1080 to 1350, DesignTemplates.size("1080x1350"))
        assertEquals(1080 to 0, DesignTemplates.size("long"))
        assertNull(DesignTemplates.size("99999x2"))
        val c = DesignTemplates.colors(Json.parseToJsonElement("""{"primary":"#E4572E","--bg":"#fff","fg":"red;}body{display:none","nope":"#000"}""") as JsonObject)
        assertEquals(mapOf("primary" to "#E4572E", "bg" to "#fff"), c)
    }

    @Test
    fun prepareSetsWidthKeepsSizeAndIconsBecomeSvg() {
        val html = """<!doctype html><html lang="zh"><head><meta name="viewport" content="width=device-width"></head><body><div class="canvas"><i data-icon="calendar"></i><i data-icon="no-such-icon-zz"></i></div></body></html>"""
        val p = DesignTemplates.prepare(html, 1080, 1440, "/*fill*/")
        assertTrue(p, """<html data-kavi-size="1080x1440" lang="zh">""" in p)
        assertTrue("""content="width=1080"""" in p && "device-width" !in p && "/*fill*/" in p)
        assertEquals(1080 to 1440, DesignTemplates.savedSize(p))
        // 再出一次图（底稿里已经有尺寸）：不会叠出两个
        assertEquals(1, Regex("data-kavi-size").findAll(DesignTemplates.prepare(p, 900, 383, "")).count())
        val icons = DesignTemplates.icons(html)
        assertTrue(icons["calendar"]!!.startsWith("<svg") && "<path" in icons["calendar"]!!)
        assertFalse("no-such-icon-zz" in icons)
    }
}
