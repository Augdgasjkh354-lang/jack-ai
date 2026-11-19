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
}
