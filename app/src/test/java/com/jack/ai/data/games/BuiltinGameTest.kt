package com.jack.ai.data.games

import com.jack.ai.ui.isGameHtml
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltinGameTest {
    @Test fun everyBuiltinGameShipsAsAGamePage() {
        BuiltinGame.entries.forEach { g ->
            val f = listOf(File("src/main/assets/${g.asset}"), File("app/src/main/assets/${g.asset}")).first { it.exists() }
            val html = f.readText()
            // 标了是游戏（游戏页认它）、版本号和代码里一致（换新版靠它）、能接模型、有兜底的规则 AI
            assertTrue(isGameHtml(html.take(4096)))
            assertTrue(g.version, "name=\"kavi-builtin\" content=\"${g.version}\"" in html.take(4096))
            assertTrue("window.jack.ask" in html && "KJ.ask(" in html)
            assertTrue("function decideAI" in html)
        }
    }
}
