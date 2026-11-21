package com.jack.ai.ui.photo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import com.jack.ai.ui.JackTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 修图：每个工具都能打开、预览、点 ✓ 落到图上；设了 SHOTS_DIR 就顺手截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        val dir = File(System.getenv("SHOTS_DIR") ?: return)
        dir.mkdirs()
        settle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir, "photo-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 手动走时钟（文字框的光标一直闪，自动等空闲会等不到头），顺便给后台线程留时间算图。 */
    private fun settle() = repeat(12) { rule.mainClock.advanceTimeBy(100); Thread.sleep(40) }
    private fun until(cond: () -> Boolean) { repeat(200) { if (cond()) return; settle() }; error("等不到") }

    /** 横着滑的条里看不见的按钮：先滑过去再点（滑的时候让时钟自己走）。 */
    private fun click(text: String) {
        val n = rule.onNodeWithText(text)
        runCatching { n.assertIsDisplayed() }.onFailure { rule.mainClock.autoAdvance = true; n.performScrollTo(); rule.mainClock.autoAdvance = false }
        n.performClick()
    }

    @Test
    fun everyToolOpensAndApplies() {
        val app = RuntimeEnvironment.getApplication()
        val src = File(app.filesDir, "photo.jpg")
        Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(Color.rgb(250, 220, 180))
            c.drawCircle(300f, 400f, 180f, android.graphics.Paint().apply { color = Color.rgb(220, 80, 60) })
        }.let { b -> src.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        var saved: File? = null
        rule.mainClock.autoAdvance = false
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { PhotoEditorScreen(src, forResult = true, onSaved = { saved = it }, onClose = {}) } }
        // 顶栏的「撤销」一开始就有；图在后台读完、底部工具条出来才能点（机器忙时要等一会儿）
        until { rule.onAllNodesWithText("裁剪").fetchSemanticsNodes().isNotEmpty() }
        shot("main")
        for (t in listOf("裁剪", "滤镜", "调节", "边框", "画笔", "文字", "马赛克", "贴纸", "虚化")) {
            click(t)
            settle()
            shot(t)
            if (t == "滤镜") { click("鲜亮"); settle(); shot("滤镜-鲜亮") }
            if (t == "贴纸") rule.onNodeWithText("😀").performClick()
            settle()
            rule.onNodeWithContentDescription("好了").performClick()
            until { rule.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        }
        shot("after")
        rule.onNodeWithText("完成").performClick()
        until { saved != null }
        assertTrue(saved!!.isFile && saved!!.length() > 1000)
    }
}
