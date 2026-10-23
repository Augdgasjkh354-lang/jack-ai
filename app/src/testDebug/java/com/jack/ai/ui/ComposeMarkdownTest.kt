package com.jack.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回答正文用 Compose 画（不用 TextView）：输出时文字和下面的框在同一帧量、同一帧画，不会叠在一起、上下乱跳。
 * 只有带公式的段落还交给 Markwon。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeMarkdownTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun plainAnswersAreComposeText() {
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                MarkdownBlocks("## 标题\n\n第一段 **加粗** 和 `代码`\n\n- 列表一\n- 列表二\n\n```\nval x = 1\n```", Color.Black, Color.LightGray)
            }
        }
        // 能按文字找到 = 是 Compose 画的（TextView 里的字找不到）
        listOf("标题", "加粗", "列表一", "val x = 1").forEach {
            assertTrue(it, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun mathStillGoesToMarkwon() {
        assertTrue(hasMath("面积是 \$\$\\pi r^2\$\$"))
        assertTrue(hasMath("当 \\(x>0\\) 时"))
        assertTrue(hasMath("设 \$x\$ 为正数"))
        // 价格不是公式
        assertTrue(!hasMath("从 \$5 到 \$10 不等"))
        assertTrue(!hasMath("普通的一段话"))
    }

    @Test
    fun whatIsBelowNeverOverlapsWhileItGrows() {
        var text by mutableStateOf("")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    MarkdownBlocks(text, Color.Black, Color.LightGray, Modifier.fillMaxWidth().testTag("answer"))
                    Text("下面的按钮", Modifier.testTag("below"))
                }
            }
        }
        repeat(30) { i ->
            text += "第 $i 句话，写得长一点好换行。" + if (i % 7 == 6) "\n\n- 列表项\n\n" else ""
            rule.mainClock.advanceTimeByFrame()
            val a = rule.onNodeWithTag("answer").getBoundsInRoot()
            val b = rule.onNodeWithTag("below").getBoundsInRoot()
            assertEquals("第 $i 帧：下面的东西要紧贴在回答下面", a.bottom.value, b.top.value, 0.5f)
        }
    }

    @Test
    fun partsAreParsed() {
        val p = parseParts("# 标题\n正文\n1. 第一\n2. 第二\n   续行\n> 引用\n---\n```\n没写完的代码")
        assertEquals(MdPart.Heading(1, "标题"), p[0])
        assertEquals(MdPart.Para("正文"), p[1])
        assertEquals(MdPart.Item("1.", true, 0, "第一"), p[2])
        assertEquals(MdPart.Item("2.", true, 0, "第二\n续行"), p[3])
        assertEquals(MdPart.Quote("引用"), p[4])
        assertEquals(MdPart.Rule, p[5])
        // 输出中代码块还没写完：照样当代码块画，不会把 ``` 露出来
        assertEquals(MdPart.Code("没写完的代码"), p[6])
    }
}
