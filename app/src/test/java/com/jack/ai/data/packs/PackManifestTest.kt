package com.jack.ai.data.packs

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 说明文件：解析、挑错、权限怎么列、新版本多要了什么、给模型的参数说明。 */
class PackManifestTest {
    private val sample = File("src/main/assets/packs/weather/kavi-pack.json").readText()

    @Test fun sampleIsValid() {
        val m = PackFormat.parse(sample).getOrThrow()
        assertEquals("weather", m.id)
        assertEquals(listOf("now", "forecast"), m.tools.map { it.name })
        assertEquals(listOf("api.open-meteo.com", "geocoding-api.open-meteo.com"), m.permissions.net)
        assertEquals("访问 2 个网站", PackFormat.permLines(m).single().title)
    }

    @Test fun problemsAreListedOneByOne() {
        val bad = """{"id":"Bad Id","name":" ","permissions":{"net":["http://x.com"],"files":"all","tools":["send_email"]},
            "tools":[{"name":"1x","description":""},{"name":"ok","description":"d","parameters":{"a b":{"type":"date"}}},{"name":"ok","description":"d"}]}"""
        val e = PackFormat.parse(bad).exceptionOrNull() as PackError
        val all = e.problems.joinToString("\n")
        listOf("id「Bad Id」", "name 不能空", "工具名「1x」", "要写 description", "参数名「a b」", "类型「date」", "「ok」重复了",
            "网站「http://x.com」", "permissions.files", "不认识「send_email」").forEach { assertTrue(it + "\n" + all, it in all) }
        assertTrue(PackFormat.parse("不是 json").exceptionOrNull()!!.message!!.contains("不是有效的 JSON"))
        assertTrue(PackFormat.parse("""{"id":"ab","name":"x","runtime":"node","tools":[{"name":"t","description":"d"}]}""").isFailure)
    }

    @Test fun hostsMatchExactlyOrByWildcard() {
        val net = listOf("api.a.com", "*.b.com")
        assertTrue(PackFormat.allows(net, "api.a.com"))
        assertTrue(PackFormat.allows(net, "API.A.COM"))
        assertFalse(PackFormat.allows(net, "x.api.a.com"))
        assertFalse(PackFormat.allows(net, "a.com"))
        assertTrue(PackFormat.allows(net, "b.com"))
        assertTrue(PackFormat.allows(net, "x.y.b.com"))
        assertFalse(PackFormat.allows(net, "evilb.com"))
    }

    @Test fun updatesSayWhatIsNew() {
        val old = PackManifest("p", "P", permissions = PackPermissions(net = listOf("a.com")))
        val new = old.copy(permissions = PackPermissions(net = listOf("a.com", "b.com"), files = "workspace", ai = true))
        assertEquals(listOf("访问 b.com", "读写对话文件夹", "用后台模型"), PackFormat.widened(old, new))
        assertTrue(PackFormat.widened(new, old).isEmpty())
        // 什么都不要的也说清楚
        assertEquals("什么权限都不要", PackFormat.permLines(old.copy(permissions = PackPermissions())).single().title)
    }

    @Test fun schemaForTheModel() {
        val t = PackToolSpec("track", "查快递", mapOf(
            "number" to PackParam("string", "单号", required = true),
            "company" to PackParam("string", enum = listOf("sf", "yt")),
        ))
        val s = PackFormat.schema(t)
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("number"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, s["properties"]!!.jsonObject["company"]!!.jsonObject["enum"]!!.jsonArray.size)
    }
}
