package com.jack.ai.data

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class AppUsageNotesTest {
    @Test fun allBundledAppsMatchTheirDeclaredPackagesWithoutChangingTheBody() {
        fun resource(path: String) = javaClass.getResourceAsStream("/knowledge/apps/$path")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val ids = resource("index.txt").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(23, ids.size)
        ids.forEach { id ->
            val parts = resource("$id/APP.md").split("---", limit = 3)
            val metadata = Yaml().load<Map<String, Any>>(parts[1])
            (metadata["packages"] as List<*>).forEach { pkg ->
                val note = BrowserFlows.builtinNotesFor("app:$pkg").single()
                assertEquals("builtin-app-$id", note.id)
                assertEquals("app:$pkg", note.site)
                assertTrue(note.text.endsWith(parts[2].trim()))
            }
        }
        val gmail = BrowserFlows.builtinNotesFor("app:com.google.android.gm").single().text
        assertTrue(gmail.length > 200)
        assertTrue(gmail.contains("抽屉或搜索页打开时"))
    }

    @Test fun packagesMatchExactlyAndDoNotBecomeWebsiteNotes() {
        assertTrue(BrowserFlows.builtinNotesFor("app:com.hpbr.bosszhipin").single().text.contains("没有不登录浏览的入口"))
        assertTrue(BrowserFlows.builtinNotesFor("app:com.hpbr.bosszhipin.fake").isEmpty())
        assertTrue(BrowserFlows.builtinNotesFor("app:unknown.app").isEmpty())
        assertTrue(BrowserFlows.builtinNotesFor("https://zhipin.com").isEmpty())
    }


    @Test fun bossPolicyUsesMainScreenCarefulPacingAndStopsAtLogin() {
        val policy = AppUsageNotes.policyForPackage("com.hpbr.bosszhipin")
        assertTrue(policy.mainScreenOnly)
        assertEquals("careful", policy.pace)
        assertEquals(1_500L, policy.actionIntervalMs)
        assertEquals(12, policy.actionLimit)
        assertEquals("微信登录", policy.signedOutMarker(listOf("欢迎使用 BOSS直聘", "微信登录", "一键登录")))
        assertNull(policy.signedOutMarker(listOf("推荐", "职位", "消息")))
        assertFalse(AppUsageNotes.policyForPackage("com.tencent.mm").mainScreenOnly)
    }

    @Test fun appNotesUseTheSharedStoreAndCombineWithFlowsAndBuiltinKnowledge() {
        val site = "app:com.tencent.mm"
        val saved = BrowserFlows.addNote(emptyList(), site, "这个账号的联系人用了备注名", now = 1)
        val all = BrowserFlows.addNote(saved, "example.com", "网站备忘", now = 2)
        val notes = BrowserFlows.notesFor(all, site) + BrowserFlows.builtinNotesFor(site)
        val flow = BrowserFlow(name = "找联系人", site = site, steps = "搜 {联系人}")
        val hint = BrowserFlows.hint(listOf(flow), notes, app = true)
        assertTrue(hint.contains("【这个 App 存了流程】"))
        assertTrue(hint.contains("action=flow"))
        assertTrue(hint.contains("【这个 App 的备忘】"))
        assertTrue(hint.contains("联系人用了备注名"))
        assertTrue(hint.contains("微信 使用知识 · Mika"))
        assertFalse(hint.contains("网站备忘"))
        assertEquals(2, all.size) // 内置知识不写回、不挤掉用户备忘。
        assertEquals(saved, BrowserFlows.notesFor(all, site))
    }
}
