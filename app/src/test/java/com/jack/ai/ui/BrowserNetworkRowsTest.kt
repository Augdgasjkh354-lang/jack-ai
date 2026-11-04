package com.jack.ai.ui

import com.wangzan101.mika.core.cap.web.CapturedResponse
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserNetworkRowsTest {
    private fun response(url: String, body: String, mime: String = "application/json",
                         truncated: Boolean = false) = CapturedResponse(
        id = url, url = url, method = "GET", type = "Fetch", status = 200, mime = mime,
        size = body.toByteArray().size.toLong(), fromCache = false,
        state = CapturedResponse.State.DONE, error = null, startedMs = 1L,
        body = body, bodyTruncated = truncated,
    )

    private fun names(result: kotlinx.serialization.json.JsonObject): List<String> {
        val table = result["tables"]!!.jsonArray.single().jsonObject
        return table["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
    }

    @Test fun nestedProductApiIsUsedForExtractTable() {
        val body = """{"data":{"items":[
            {"skuName":"保温杯 A","jdPrice":"29.90","shopName":"店甲"},
            {"skuName":"保温杯 B","jdPrice":"35.50","shopName":"店乙"}
        ]}}"""
        val found = BrowserNetworkRows.table(
            listOf(response("https://api.jd.com/search/products", body)),
            "https://www.jd.com/search?keyword=cup",
        )!!
        assertEquals(listOf("保温杯 A", "保温杯 B"), names(found))
        val row = found["tables"]!!.jsonArray.single().jsonObject["rows"]!!.jsonArray.first().jsonArray
        assertEquals("29.90", row[1].jsonPrimitive.content)
        assertEquals("店甲", row[2].jsonPrimitive.content)
    }

    @Test fun crossSiteSensitiveOrUnstructuredResponseFallsBackToPage() {
        val body = """{"data":{"items":[{"name":"A","price":1},{"name":"B","price":2}]}}"""
        assertNull(BrowserNetworkRows.table(listOf(
            response("https://elsewhere.com/api/items", body),
            response("https://api.jd.com/account/profile", body),
            response("https://api.jd.com/search", "<html>not-json</html>", "text/html"),
        ), "https://www.jd.com/search"))
    }

    @Test fun latestCompleteListWinsAndTruncatedBodyIsIgnored() {
        val earlier = """{"items":[{"title":"旧 A","price":10},{"title":"旧 B","price":20}]}"""
        val later = """{"items":[{"title":"新 A","price":11},{"title":"新 B","price":22}]}"""
        val found = BrowserNetworkRows.table(listOf(
            response("https://api.jd.com/api/list?first", earlier),
            response("https://api.jd.com/api/list?second", later),
            response("https://api.jd.com/api/list?partial", earlier, truncated = true),
        ), "https://www.jd.com/search")!!
        assertEquals(listOf("新 A", "新 B"), names(found))
    }
}
