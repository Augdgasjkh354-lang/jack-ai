package com.jack.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** revise_file：按页改 PPT、按表改 Excel、改整份设置，只动写到的部分。 */
class ReviseTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val ppt = obj("""{"filename":"汇报","theme":"blue","slides":[
        {"layout":"title","title":"封面"},
        {"layout":"content","title":"第二页","bullets":["a","b"],"notes":"讲稿"},
        {"layout":"content","title":"第三页","bullets":["c"]},
        {"layout":"end","title":"谢谢"}]}""")

    private fun titles(o: JsonObject) = (o["slides"] as JsonArray).map { it.jsonObject["title"]!!.jsonPrimitive.content }

    @Test
    fun slideOpsRunInOrderAndKeepTheRest() {
        val (out, done) = revise("create_ppt", ppt, obj("""{"set":{"theme":"red","transition":"morph"},"slides":[
            {"op":"update","page":2,"slide":{"layout":"cards","notes":null}},
            {"op":"insert","page":3,"slide":{"layout":"quote","title":"金句"}},
            {"op":"delete","page":4},
            {"op":"move","page":4,"to":1}]}"""))
        assertEquals("red", out["theme"]!!.jsonPrimitive.content)
        assertEquals("morph", out["transition"]!!.jsonPrimitive.content)
        assertEquals("汇报", out["filename"]!!.jsonPrimitive.content)
        assertEquals(listOf("谢谢", "封面", "第二页", "金句"), titles(out))
        val second = (out["slides"] as JsonArray)[2].jsonObject
        assertEquals("cards", second["layout"]!!.jsonPrimitive.content)
        assertEquals(2, (second["bullets"] as JsonArray).size)  // 没写的字段原样
        assertFalse("notes" in second)                           // 写 null 去掉
        assertEquals(6, done.size)
    }

    @Test
    fun excelSheetsAreMergedAddedOrDeleted() {
        val xl = obj("""{"filename":"账","sheets":[{"name":"明细","rows":[["a","b"],["1","2"]]},{"name":"旧表","rows":[["x"]]}]}""")
        val (out, _) = revise("create_excel", xl, obj("""{"sheets":[
            {"name":"明细","total_row":true,"charts":[{"type":"column","title":"t","category_column":"a","value_columns":["b"]}]},
            {"name":"旧表","delete":true},
            {"name":"汇总","rows":[["合计"]]}]}"""))
        val sheets = (out["sheets"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("明细", "汇总"), sheets.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("true", sheets[0]["total_row"]!!.jsonPrimitive.content)
        assertEquals(2, (sheets[0]["rows"] as JsonArray).size)
    }

    @Test
    fun wrongRequestsExplainThemselves() {
        val e1 = assertThrows(IllegalArgumentException::class.java) { revise("create_ppt", ppt, obj("""{"slides":[{"op":"delete","page":9}]}""")) }
        assertTrue(e1.message!!.contains("一共 4 页"))
        val e2 = assertThrows(IllegalArgumentException::class.java) { revise("create_word", obj("""{"markdown":"# a"}"""), obj("""{"set":{"transition":"fade"}}""")) }
        assertTrue(e2.message!!.contains("cover"))
        val e3 = assertThrows(IllegalArgumentException::class.java) { revise("create_word", obj("""{"markdown":"# a"}"""), obj("""{"slides":[{"op":"delete","page":1}]}""")) }
        assertTrue(e3.message!!.contains("只能用在 PPT"))
        assertThrows(IllegalArgumentException::class.java) { revise("create_ppt", ppt, obj("{}")) }
        // Word 加封面目录
        val (w, _) = revise("create_word", obj("""{"markdown":"# a"}"""), obj("""{"set":{"cover":{"title":"报告"},"toc":true}}"""))
        assertEquals("报告", w["cover"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("# a", w["markdown"]!!.jsonPrimitive.content)
    }
}
