package com.jack.ai.ui

import com.jack.ai.data.Verify
import com.jack.ai.data.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShimTest {
    @Test
    fun shimGoesIntoHeadWithViewport() {
        val out = withShim("<html><head><title>x</title></head><body>hi</body></html>")
        assertTrue(out.startsWith("<html><head><meta name=\"viewport\""))
        assertTrue(out.indexOf("window.jack") < out.indexOf("<title>"))
        // 自己写了 viewport 就不再加
        val own = withShim("<head><meta name='viewport' content='width=device-width'></head><body></body>")
        assertEquals(1, Regex("viewport").findAll(own).count())
        // 没有 head 的片段也能用
        assertTrue(withShim("<div>hi</div>").endsWith("<div>hi</div>"))
    }

    @Test
    fun htmlCheck() {
        assertEquals(emptyList<String>(), Verify.html("""<body><script src="https://cdn.jsdelivr.net/npm/chart.js"></script><div></div></body>"""))
        val bad = Verify.html("""<body><img src="https://evil.example.com/x.png"><script>var a=1</body>""")
        assertTrue(bad.toString(), bad.any { "evil.example.com" in it })
        assertTrue(bad.toString(), bad.any { "没配对" in it })
    }

    @Test
    fun appSourceIsTheHtml() {
        val args = JsonObject(mapOf("title" to JsonPrimitive("计算器"), "html" to JsonPrimitive("<b>1</b>")))
        assertEquals("<b>1</b>", Workspace.sourceText("create_app", args))
        assertEquals("<b>2</b>", (Workspace.argsFromText("create_app", args, "<b>2</b>")["html"] as JsonPrimitive).content)
    }
}
