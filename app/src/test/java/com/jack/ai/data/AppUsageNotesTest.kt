package com.jack.ai.data

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import com.jack.ai.data.browser.BrowserFlow
import com.jack.ai.data.browser.BrowserFlows
import com.jack.ai.data.browser.SiteNote

class AppUsageNotesTest {
    @Test fun allBundledAppsMatchTheirDeclaredPackagesWithoutChangingTheBody() {
        fun resource(path: String) = javaClass.getResourceAsStream("/knowledge/apps/$path")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val ids = resource("index.txt").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(50, ids.size)
        ids.forEach { id ->
            val parts = resource("$id/APP.md").split("---", limit = 3)
            val metadata = Yaml().load<Map<String, Any>>(parts[1])
            (metadata["packages"] as List<*>).forEach { pkg ->
                val note = BrowserFlows.builtinNotesFor("app:$pkg").single()
                assertEquals("builtin-app-$id", note.id)
                assertEquals("app:$pkg", note.site)
                assertTrue(note.text.contains(parts[2].trim()))
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
        assertFalse(BrowserFlows.builtinNotesFor("https://zhipin.com").any { it.id.startsWith("builtin-app-") })
    }


    @Test fun bossPolicyUsesMainScreenCarefulPacingAndStopsAtLogin() {
        val policy = AppUsageNotes.policyForPackage("com.hpbr.bosszhipin")
        assertTrue(policy.mainScreenOnly)
        assertEquals("careful", policy.pace)
        assertEquals(1_500L, policy.actionIntervalMs)
        assertEquals(500, policy.actionLimit)
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

    @Test fun siteKnowledgeMatchesDomainsAndSubdomainsMostSpecificFirst() {
        fun one(url: String) = BrowserFlows.builtinNotesFor(url).single()
        assertEquals("builtin-site-zhihu.com", one("https://www.zhihu.com/question/1").id)
        assertEquals("builtin-site-tieba.baidu.com", one("https://tieba.baidu.com/f?kw=x").id)
        assertEquals("builtin-site-baidu.com", one("https://www.baidu.com/s?wd=x").id)
        assertEquals("builtin-site-jd.com", one("https://item.jd.com/1.html").id)
        assertEquals("builtin-site-youtube.com", one("https://youtu.be/abc").id)
        assertTrue(one("https://www.zhihu.com/").text.startsWith("【知乎 网站使用知识 · Mika】"))
        assertTrue(BrowserFlows.builtinNotesFor("https://notzhihu.com/").isEmpty())
        assertTrue(BrowserFlows.builtinNotesFor("https://example.com/").isEmpty())
    }

    @Test fun referencedFilesAreAppendedToTheBody() {
        val boss = BrowserFlows.builtinNotesFor("https://www.zhipin.com/web/geek/job").single().text
        assertTrue(boss.contains("### 附：references/cities.md"))
        assertTrue(boss.contains("### 附：references/filters.md"))
        val wechat = BrowserFlows.builtinNotesFor("app:com.tencent.mm").single().text
        assertTrue(wechat.contains("### 附：references/send-moments.md"))
    }

    @Test fun longSiteNotesAreSeparatedByBlankLines() {
        val notes = BrowserFlows.builtinNotesFor("https://www.zhihu.com/")
        val hint = BrowserFlows.hint(emptyList(), notes + SiteNote(site = "zhihu.com", text = "短备忘", at = 1))
        assertTrue(hint.contains("\n\n短备忘"))
    }
}
