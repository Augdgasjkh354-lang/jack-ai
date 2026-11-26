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
            // 标了是游戏（游戏页认它）、用 jack.ask 问模型、有兜底的规则机器人
            assertTrue(isGameHtml(html.take(4096)))
            assertTrue("jack.ask(" in html)
            assertTrue("botDecide" in html)
        }
    }
}
