package com.jack.ai.data

import org.junit.Assume.assumeTrue
import org.junit.Test

/** 真连 12306 查一次（只在设了 RAIL_LIVE=1 时跑，CI 里跳过）。 */
class RailLiveCheck {
    @Test
    fun queryForReal() {
        assumeTrue(System.getenv("RAIL_LIVE") == "1")
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rail-live").apply { mkdirs() }
        val date = java.time.LocalDate.now().plusDays(5).toString()
        val args = kotlinx.serialization.json.buildJsonObject {
            put("from", kotlinx.serialization.json.JsonPrimitive("北京"))
            put("to", kotlinx.serialization.json.JsonPrimitive("上海"))
            put("date", kotlinx.serialization.json.JsonPrimitive(date))
            put("types", kotlinx.serialization.json.JsonPrimitive("G"))
            put("depart_before", kotlinx.serialization.json.JsonPrimitive("08:00"))
        }
        println(com.jack.ai.tools.trainText(dir, args))
    }
}
