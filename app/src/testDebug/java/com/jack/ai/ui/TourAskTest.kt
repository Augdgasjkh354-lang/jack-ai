package com.jack.ai.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 新手教学：填了 DeepSeek Key 才问，问一次；选「好」就开始，选「不用了」以后不再问。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TourAskTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @After fun reset() { Tour.step = -1 }

    private fun vm(key: String, asked: Boolean = false): ChatViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        // 空白页的建议弹幕一直在飘，界面永远「不空闲」，测试等不到；这里关掉
        Store(app).saveSettings(Store(app).loadSettings().copy(apiKey = key, tourAsked = asked, bizStarts = false))
        return ChatViewModel(app).also { m ->
            rule.setContent {
                val state by m.state.collectAsState()
                JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { ChatScreen(m, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
            }
        }
    }

    @Test
    fun noKeyNoQuestion() {
        vm("")
        assertEquals(0, rule.onAllNodesWithText("认识一下 Kavi？").fetchSemanticsNodes().size)
    }

    @Test
    fun yesStartsTheTourAndIsRemembered() {
        val m = vm("sk-test")
        rule.onNodeWithText("好，带我看看").performClick()
        rule.waitForIdle()
        assertTrue(m.state.value.settings.tourAsked)
        assertEquals(0, Tour.step)
        rule.onNodeWithText("在这里问 Kavi").assertExists()
        rule.onNodeWithText("下一步").performClick()
        rule.onNodeWithText("加图片和文件").assertExists()
        rule.onNodeWithText("跳过").performClick()
        assertEquals(-1, Tour.step)
    }

    @Test
    fun noThanksIsRememberedAcrossRestarts() {
        val m = vm("sk-test")
        rule.onNodeWithText("不用了").performClick()
        rule.waitForIdle()
        assertEquals(-1, Tour.step)
        assertTrue(Store(RuntimeEnvironment.getApplication()).loadSettings().tourAsked)
        assertTrue(m.state.value.settings.tourAsked)
    }

    @Test
    fun askedBeforeNoQuestion() {
        vm("sk-test", asked = true)
        assertEquals(0, rule.onAllNodesWithText("认识一下 Kavi？").fetchSemanticsNodes().size)
    }
}
