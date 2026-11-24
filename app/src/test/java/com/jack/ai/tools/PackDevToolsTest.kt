package com.jack.ai.tools

import android.app.Application
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.SkillIds
import com.jack.ai.data.Store
import com.jack.ai.data.allSkills
import com.jack.ai.data.packs.PackInstallPrompt
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Kavi 自己做工具包：test_pack 检查、试跑，install_pack 交给用户装。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PackDevToolsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private var answer = true
    private var asked = 0

    private fun session(): ToolSession {
        val ui = object : ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) {}
            override suspend fun confirm(title: String, body: String, action: String): Boolean { asked++; return answer }
        }
        val settings = Store(ctx).loadSettings()
        return ToolSession(BackgroundToolHost(ctx), settings, Conversation(), null, settings.allSkills().filter { it.id == SkillIds.PACK_DEV }, ui)
    }

    private fun write(s: ToolSession, manifest: String, code: String) {
        val d = File(s.wsDir, "counter").apply { mkdirs() }
        File(d, "kavi-pack.json").writeText(manifest)
        File(d, "main.js").writeText(code)
    }

    private val manifest = """{"id":"counter","name":"计数器","version":"1.0.0","description":"数数",
        "tools":[{"name":"bump","description":"加一","parameters":{"by":{"type":"integer","required":true}}}]}"""
    private val code = """export async function bump({ by }, kavi) { const n = kavi.store.get("n", 0) + by; kavi.store.set("n", n); console.log("n=" + n); return { n }; }"""

    @Test fun testChecksThenRuns() = runBlocking {
        val s = session()
        write(s, manifest, code)
        assertTrue(s.execute("test_pack", """{"folder":"counter"}""").contains("说明文件没问题"))
        val r = s.execute("test_pack", """{"folder":"counter","tool":"bump","args":{"by":2}}""")
        assertTrue(r, r.contains("跑通了") && r.contains("\"n\": 2") && r.contains("n=2"))
        assertTrue(s.execute("test_pack", """{"folder":"counter","tool":"bump","args":{}}""").contains("缺了必填参数：by"))
        assertTrue(s.execute("test_pack", """{"folder":"counter","tool":"nope"}""").contains("有：bump"))
        assertTrue(s.execute("test_pack", """{"folder":"../.."}""").contains("没有"))
        write(s, manifest.replace("\"bump\"", "\"1bump\""), code)
        assertTrue(s.execute("test_pack", """{"folder":"counter"}""").contains("说明文件有这些问题"))
        write(s, manifest, "export async function bump() { throw new Error('坏了') }")
        val bad = s.execute("test_pack", """{"folder":"counter","tool":"bump","args":{"by":1}}""")
        assertTrue(bad, bad.contains("坏了"))
    }

    @Test fun installAsksTheUserFirst() = runBlocking {
        val s = session()
        write(s, manifest, code)
        answer = false
        val no = s.execute("install_pack", """{"folder":"counter"}""")
        assertTrue(no, no.contains("用户没有装"))
        assertTrue(Store(ctx).loadSettings().packs.isEmpty())
        answer = true
        val r = s.execute("install_pack", """{"folder":"counter"}""")
        assertTrue(r, r.contains("已装好") && r.contains("pack_counter__bump"))
        assertEquals("asked", 2, asked)
        val p = Store(ctx).loadSettings().packs.single()
        assertEquals("kavi", p.source)
        assertTrue(File(com.jack.ai.data.packs.Packs.dir(ctx, "counter"), "main.js").isFile)
        // 再交一次就是更新
        write(s, manifest.replace("1.0.0", "1.1.0"), code)
        val up = s.execute("install_pack", """{"folder":"counter"}""")
        assertTrue(up, up.contains("已更新到 v1.1.0"))
    }

    @Test fun confirmPageAnswersWhenTheAppIsOpen() = runBlocking {
        val s = session()
        write(s, manifest, code)
        // 模拟界面在听：看到请求就点「装」
        val seen = async { PackInstallPrompt.pending.filterNotNull().first() }
        val listener = launch { PackInstallPrompt.pending.collect { it?.reply(true) } }
        val r = s.execute("install_pack", """{"folder":"counter"}""")
        listener.cancel()
        assertTrue(r, r.contains("已装好"))
        assertEquals("计数器", seen.await().staged.manifest.name)
        assertEquals(0, asked)
    }

    @Test fun operitScriptIsConvertedOnInstall() = runBlocking {
        val s = session()
        s.wsDir.mkdirs()
        java.io.File(s.wsDir, "hello.js").writeText("/* METADATA\n{ name: hello, description: 打招呼, tools: [ { name: hi, description: 说你好, parameters: [] } ] }\n*/\n" +
            "exports.hi = async () => ({ success: true, message: '你好' });")
        val r = s.execute("install_pack", """{"folder":"hello.js"}""")
        assertTrue(r, r.contains("已装好") && r.contains("pack_op_hello__hi"))
        val p = Store(ctx).loadSettings().packs.single()
        assertEquals("operit", p.source)
        assertTrue(File(com.jack.ai.data.packs.Packs.dir(ctx, p.id), "original.js").isFile)
    }
}
