package com.jack.ai.data.packs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** App 自带的示例「天气」：用假的 Open-Meteo 回应跑它的代码。 */
class WeatherPackTest {
    private val dir = File("src/main/assets/packs/weather")
    private val m = PackFormat.parse(File(dir, "kavi-pack.json").readText()).getOrThrow()
    private val code = File(dir, "main.js").readText()

    private class Host : PackHost {
        val store = mutableMapOf<String, JsonElement>()
        val urls = mutableListOf<String>()
        override fun call(op: String, arg: JsonElement): JsonElement {
            val k = arg.jsonObject["key"]?.jsonPrimitive?.content
            return when (op) {
                "store.get" -> store[k] ?: JsonNull
                "store.set" -> { store[k!!] = arg.jsonObject["value"]!!; JsonNull }
                else -> JsonNull
            }
        }
        override suspend fun callAsync(op: String, arg: JsonElement): JsonElement {
            val url = arg.jsonObject["url"]!!.jsonPrimitive.content
            urls += url
            val q = arg.jsonObject["query"]!!.jsonObject
            val body = when {
                "geocoding" in url && q["name"]!!.jsonPrimitive.content == "火星" -> """{"generationtime_ms":0.1}"""
                "geocoding" in url -> """{"results":[{"name":"上海","latitude":31.22,"longitude":121.46,"country":"中国","admin1":"上海","timezone":"Asia/Shanghai"}]}"""
                "current" in q -> """{"current":{"time":"2026-11-24T10:00","temperature_2m":12.5,"apparent_temperature":10.1,"relative_humidity_2m":70,"weather_code":61,"wind_speed_10m":9.4},
                    "daily":{"temperature_2m_max":[15.0],"temperature_2m_min":[9.0],"precipitation_probability_max":[80]}}"""
                else -> """{"daily":{"time":["2026-11-24","2026-11-25"],"weather_code":[3,0],"temperature_2m_max":[15,17],"temperature_2m_min":[9,8],"precipitation_probability_max":[20,0]}}"""
            }
            return buildJsonObject { put("status", 200); put("ok", true); put("body", body) }
        }
    }

    private fun run(tool: String, args: JsonObject, host: Host) = runBlocking { PackRuntime.run(m, code, tool, args, emptyMap(), host) }

    @Test fun nowAndForecastRememberTheCity() {
        val h = Host()
        val now = run("now", buildJsonObject { put("city", "上海") }, h)
        assertTrue(now.text, now.ok)
        assertTrue(now.text, "小雨，12.5°C，体感 10.1°C" in now.text && "降水概率 80%" in now.text && now.text.startsWith("上海，中国"))
        assertEquals(JsonPrimitive("上海"), h.store["lastCity"])
        // 不填城市用上次的；经纬度存过了，不再查城市
        val f = run("forecast", buildJsonObject { put("days", 2) }, h)
        assertTrue(f.text, f.ok)
        assertTrue(f.text, "11-24 周二  阴" in f.text && "11-25 周三  晴" in f.text)
        assertEquals(1, h.urls.count { "geocoding" in it })
        assertTrue(h.urls.all { m.permissions.net.any { d -> it.startsWith("https://$d/") } })
    }

    @Test fun unknownPlaceIsExplained() {
        val r = run("now", buildJsonObject { put("city", "火星") }, Host())
        assertFalse(r.ok)
        assertTrue(r.text, "没找到「火星」" in r.text)
        val none = run("now", buildJsonObject { }, Host())
        assertTrue(none.text, "要告诉我查哪个城市" in none.text)
    }
}
