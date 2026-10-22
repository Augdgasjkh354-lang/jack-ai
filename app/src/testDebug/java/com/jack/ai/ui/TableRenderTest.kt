package com.jack.ai.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 回答里的表格在聊天列表里能正常画出来（含宽表横向滑动、表格前后夹着文字）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TableRenderTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun tableInsideAnAnswerRenders() {
        val md = "价格对比：\n| 模型 | 输入 | 输出 | 缓存命中 | 备注 |\n|---|---:|---:|---:|---|\n" +
            "| **Flash** | 1 元 | 2 元 | 0.1 元 | 默认用这个，日常够用 |\n| Pro | 4 元 | 16 元 | 0.4 元 | `复核` 时用 |\n\n以上是官方价。"
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                LazyColumn {
                    item { MarkdownBlocks(md, Color.Black, Color.LightGray, Modifier.fillMaxWidth()) }
                }
            }
        }
        rule.onNodeWithText("缓存命中").assertIsDisplayed()
        rule.onNodeWithText("Flash").assertIsDisplayed()
    }
}
