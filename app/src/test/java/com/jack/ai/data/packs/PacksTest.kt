package com.jack.ai.data.packs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 装工具包（压缩包、示例）、交给模型的工具名、App 里 kavi.* 的权限拦截。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PacksTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val manifest = """{"id":"kuaidi","name":"快递","tools":[{"name":"track","description":"查快递","parameters":{"no":{"type":"string","required":true}}}],
        "config":[{"key":"apiKey","label":"Key","secret":true,"required":true}]}"""

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun zipInAFolderInstallsAndKeepsConfig() {
        val s = Packs.stageZip(ctx, ByteArrayInputStream(zip("kuaidi-main/kavi-pack.json" to manifest, "kuaidi-main/main.js" to "export function track(){return 1}",
            "kuaidi-main/README.md" to "# 快递", "__MACOSX/._x" to "junk")))
        assertEquals("kuaidi", s.manifest.id)
        assertEquals(listOf("README.md", "kavi-pack.json", "main.js"), s.files)
        assertEquals("# 快递", s.readme)
        val old = InstalledPack(s.manifest, enabled = false, installedAt = 5, config = mapOf("apiKey" to "k", "gone" to "x"))
        val p = Packs.install(ctx, s, old, now = 9)
        assertEquals(false, p.enabled)
        assertEquals(mapOf("apiKey" to "k"), p.config)
        assertEquals(5L, p.installedAt)
        assertTrue(File(Packs.dir(ctx, "kuaidi"), "main.js").isFile)
        assertTrue(!s.dir.exists())
        Packs.remove(ctx, "kuaidi")
        assertTrue(!Packs.dir(ctx, "kuaidi").exists())
    }

    @Test fun badZipsAreRefused() {
        assertThrows(PackError::class.java) { Packs.stageZip(ctx, ByteArrayInputStream(zip("../evil.js" to "x", "kavi-pack.json" to manifest))) }
        val noMain = assertThrows(PackError::class.java) { Packs.stageZip(ctx, ByteArrayInputStream(zip("kavi-pack.json" to manifest))) }
        assertTrue(noMain.message!!.contains("main.js"))
        val notPack = assertThrows(PackError::class.java) { Packs.stageZip(ctx, ByteArrayInputStream(zip("a.txt" to "x"))) }
        assertTrue(notPack.message!!.contains("kavi-pack.json"))
    }

    @Test fun sampleShipsWithTheApp() {
        val s = Packs.stageAsset(ctx, "weather")
        assertEquals("天气", s.manifest.name)
        assertTrue(s.icon != null)
        Packs.discard(s)
    }

    @Test fun githubSpecs() {
        assertEquals(Triple("jack/packs", "", ""), Packs.parseSpec("jack/packs"))
        assertEquals(Triple("jack/packs", "kuaidi", "dev"), Packs.parseSpec("jack/packs/kuaidi@dev"))
        assertEquals(Triple("jack/packs", "a/b", "main"), Packs.parseSpec("https://github.com/jack/packs/tree/main/a/b"))
        assertEquals(Triple("jack/packs", "", ""), Packs.parseSpec("github.com/jack/packs.git"))
        assertNull(Packs.parseSpec("jack"))
        assertNull(Packs.parseSpec("ja ck/x"))
    }

    @Test fun onlyReadyPacksReachTheModel() {
        val m = PackFormat.parse(manifest).getOrThrow()
        val needsKey = InstalledPack(m)
        val ready = InstalledPack(m.copy(id = "my-pack"), config = mapOf("apiKey" to "k"))
        val off = ready.copy(manifest = m.copy(id = "off"), enabled = false)
        val defs = Packs.toolDefs(listOf(needsKey, ready, off))
        assertEquals(listOf("pack_my_pack__track"), defs.map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(defs[0]["function"]!!.jsonObject["description"]!!.jsonPrimitive.content.startsWith("【工具包「快递」】"))
        assertEquals("my-pack", Packs.find(listOf(needsKey, ready), "pack_my_pack__track")!!.first.id)
        assertNull(Packs.find(listOf(needsKey), "pack_kuaidi__track"))
    }

    private fun host(perms: PackPermissions, ws: File? = null) =
        AppPackHost(ctx, Settings(), InstalledPack(PackManifest("t1", "T", permissions = perms, tools = listOf(PackToolSpec("x", "d")))), ws, null)

    @Test fun filesAndStoreStayInsideThePack() {
        val none = host(PackPermissions())
        assertTrue(runCatching { none.call("files.read", buildJsonObject { put("path", "a.txt") }) }.exceptionOrNull()!!.message!!.contains("没有声明 files"))
        val ws = File(ctx.cacheDir, "ws").apply { mkdirs() }
        val own = host(PackPermissions(files = "pack"), ws)
        own.call("files.write", buildJsonObject { put("path", "notes/a.txt"); put("text", "你好") })
        assertEquals("你好", own.call("files.read", buildJsonObject { put("path", "notes/a.txt") }).jsonPrimitive.content)
        assertEquals(listOf("notes/"), own.call("files.list", buildJsonObject { put("path", "") }).jsonArray.map { it.jsonPrimitive.content })
        assertTrue(runCatching { own.call("files.read", buildJsonObject { put("path", "../../x") }) }.exceptionOrNull()!!.message!!.contains("外面"))
        assertTrue(runCatching { own.call("files.write", buildJsonObject { put("path", "workspace/a"); put("text", "x") }) }.exceptionOrNull()!!.message!!.contains("workspace"))
        val wide = host(PackPermissions(files = "workspace"), ws)
        wide.call("files.write", buildJsonObject { put("path", "workspace/out.txt"); put("text", "ok") })
        assertEquals("ok", File(ws, "out.txt").readText())

        own.call("store.set", buildJsonObject { put("key", "n"); put("value", 3) })
        assertEquals(JsonPrimitive(3), own.call("store.get", buildJsonObject { put("key", "n") }))
        own.call("store.set", buildJsonObject { put("key", "n"); put("value", JsonNull) })
        assertEquals(JsonNull, own.call("store.get", buildJsonObject { put("key", "n") }))
        assertTrue(runCatching { own.call("notify", buildJsonObject { put("title", "x") }) }.exceptionOrNull()!!.message!!.contains("notify"))
        Packs.remove(ctx, "t1")
    }

    @Test fun httpOnlyReachesDeclaredSites() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"a":1}""").addHeader("Content-Type", "application/json"))
            enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:1/elsewhere"))
            start()
        }
        val h = host(PackPermissions(net = listOf("localhost")))
        val ok = h.callAsync("http", buildJsonObject { put("url", server.url("/x").toString()); put("query", buildJsonObject { put("q", "天气") }) }).jsonObject
        assertEquals("200", ok["status"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1}""", ok["body"]!!.jsonPrimitive.content)
        assertEquals("/x?q=%E5%A4%A9%E6%B0%94", server.takeRequest().path)
        val jump = runCatching { h.callAsync("http", buildJsonObject { put("url", server.url("/y").toString()) }) }.exceptionOrNull()
        assertTrue(jump?.message, jump!!.message!!.contains("没声明的 127.0.0.1"))
        val blocked = runCatching { h.callAsync("http", buildJsonObject { put("url", "https://example.com/") }) }.exceptionOrNull()
        assertTrue(blocked!!.message!!.contains("没有声明能访问 example.com"))
        val noAi = runCatching { h.callAsync("ai", buildJsonObject { put("prompt", "x") }) }.exceptionOrNull()
        assertTrue(noAi!!.message!!.contains("ai 权限"))
        server.shutdown()
    }
}
