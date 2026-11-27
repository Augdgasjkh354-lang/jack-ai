package com.jack.ai.data.games

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.Project
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 游戏在哪个对话做的都行：任何对话里写「游戏/名字.html」都找得到；隐私项目里的不算。 */
@RunWith(RobolectricTestRunner::class)
class GameFilesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val game = """<html><head><meta name="kavi-kind" content="game"><title>x</title></head><body></body></html>"""

    private fun put(dir: String, name: String, html: String) = File(Workspace.dir(app, dir).apply { mkdirs() }, name).apply { writeText(html) }

    @Test fun findsGamesAcrossConversationsButNotInPrivateProjects() {
        put("conv1", "贪吃蛇.html", game)
        put("conv2", "贷款计算器.html", "<html><head></head></html>")
        put("p_secret", "秘密游戏.html", game)
        Store(app).saveProjects(listOf(Project(id = "secret", name = "私", isolated = true)))

        assertEquals("贪吃蛇.html", GameFiles.find(app, "游戏/贪吃蛇.html")?.name)
        assertEquals("贪吃蛇.html", GameFiles.find(app, "贪吃蛇")?.name)
        // 不是游戏的小工具、隐私项目里的游戏都找不到
        assertNull(GameFiles.find(app, "游戏/贷款计算器.html"))
        assertNull(GameFiles.find(app, "游戏/秘密游戏.html"))
        val note = GameFiles.note(app)
        assertTrue(note, "游戏/贪吃蛇.html" in note && "秘密" !in note)
        // 在做它的那个对话里列文件时不重复提
        assertEquals("", GameFiles.note(app, except = Workspace.dir(app, "conv1")))
    }

    @Test fun outdatedBuiltinIsKeptAsOldVersion() {
        val v1 = put("game_holdem", "德州扑克.html", game.replace("<title>", "<meta name=\"kavi-builtin\" content=\"holdem-1\"><title>"))
        // 版本对得上：不动
        assertNull(GameFiles.keepOutdated(v1, "holdem-1"))
        val kept = GameFiles.keepOutdated(v1, "holdem-2")
        assertEquals("德州扑克（旧版）.html", kept?.name)
        assertTrue(!v1.exists())
        // 再换一版：已有的旧版不覆盖，往后编号（没写版本的老文件也算旧）
        put("game_holdem", "德州扑克.html", game)
        assertEquals("德州扑克（旧版） 2.html", GameFiles.keepOutdated(v1, "holdem-3")?.name)
    }
}
