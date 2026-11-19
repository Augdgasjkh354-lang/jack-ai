package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignKaviTest {
    @Test
    fun parsesModelReplyAndTarget() {
        val r = DesignKavi.parse("```json\n{\"html\":\"<h1 data-slot=\\\"title\\\">新</h1>\",\"note\":\"标题更短了\"}\n```")!!
        assertEquals("<h1 data-slot=\"title\">新</h1>", r.html)
        assertEquals("标题更短了", r.note)
        assertNull(DesignKavi.parse("{\"html\":\"没有标签\"}"))
        assertNull(DesignKavi.parse("不是 JSON"))
        val t = DesignKavi.parseTarget("""{"scope":"element","path":[2,0],"html":"<h1>a</h1>","css":".x{}","slot":"title","kind":"text","text":"a","w":1080,"h":1440}""")!!
        assertEquals(listOf(2, 0), t.path)
        assertEquals(1080, t.w)
    }

    @Test
    fun reviewItemsMapToPiecesAndPage() {
        val raw = """{"items":[{"n":3,"problem":"标题不够大","fix":"字号加到 120px"},{"n":0,"problem":"太挤","fix":"整体留白加大"},{"n":99,"problem":"不存在","fix":"x"},{"n":2,"problem":"","fix":"空的不要"}]}"""
        val a = DesignKavi.parseReview(raw, mapOf(3 to listOf(2), 2 to listOf(1, 0)))
        assertEquals(2, a.size)
        assertEquals(listOf(2), a[0].path)
        assertEquals(emptyList<Int>(), a[1].path)
        assertEquals(listOf(1, 2), a.map { it.label })
    }
}
