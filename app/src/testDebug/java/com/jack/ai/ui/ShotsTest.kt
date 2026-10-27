package com.jack.ai.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.jack.ai.ChatViewModel
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Store
import com.jack.ai.data.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 本地看界面用：SHOTS_DIR 设了才跑，把几个页面画成 PNG。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun save(name: String) {
        val dir = File(System.getenv("SHOTS_DIR") ?: return)
        dir.mkdirs()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun vm(convs: List<Conversation>): ChatViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        Store(app).saveConversations(convs)
        return ChatViewModel(app)
    }

    private fun show(vm: ChatViewModel, mode: ThemeMode) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(mode) {
                ChatScreen(vm, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test
    fun chat() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val mode = if (!System.getenv("SHOTS_DARK").isNullOrEmpty()) ThemeMode.DARK else ThemeMode.LIGHT
        val answer = """## 长期保持高效和专注，可以从三个方面入手

1. **设计适合自己的工作节奏**：找到精力最充沛的时间段，专注处理最重要的任务；用固定流程减少决策成本。
2. **管理注意力，而不是时间**：减少不必要的干扰，一次只做一件事，借助番茄工作法训练专注。
3. **保持身心的稳定状态**：充足的睡眠、规律的运动和健康的饮食，是高效工作的底层支持。

---

**高效不是一时的冲刺，而是一种可持续的生活方式。** 当你建立起适合自己的节奏，专注就会成为习惯。"""
        val c = Conversation(title = "保持高效", messages = listOf(
            ChatMessage(role = "user", content = "如何在工作中保持长期的高效和专注？"),
            ChatMessage(role = "assistant", content = answer, model = "deepseek-v4-flash",
                searches = listOf(SearchRecord("高效 专注 方法"), SearchRecord("番茄工作法"))),
        ))
        val vm = vm(listOf(c))
        vm.select(c.id)
        show(vm, mode)
        save("chat-" + mode.name.lowercase())
    }

    @Test
    fun empty() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val mode = if (!System.getenv("SHOTS_DARK").isNullOrEmpty()) ThemeMode.DARK else ThemeMode.LIGHT
        val vm = vm(emptyList())
        show(vm, mode)
        save("empty-" + mode.name.lowercase())
    }
}
