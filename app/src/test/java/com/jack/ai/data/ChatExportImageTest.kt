package com.jack.ai.data

import com.jack.ai.data.ChatExport.ImageOptions
import com.jack.ai.data.ChatExport.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatExportImageTest {
    private val msgs = listOf(
        ChatMessage(role = "user", content = "第一问"),
        ChatMessage(role = "assistant", content = "第一答"),
        ChatMessage(role = "user", content = "把这个月的花销做成表"),
        ChatMessage(role = "assistant", content = "做好了。10 月一共花了 **¥6,842**：\n\n- 房租 ¥3,200\n- 餐饮 ¥1,486", model = "deepseek-v4-flash"),
    )

    @Test fun lastTurnStartsAtLastQuestion() {
        assertEquals(listOf("把这个月的花销做成表", msgs[3].content), ChatExport.pickMessages(msgs, ImageOptions(lastTurn = true)).map { it.content })
    }

    @Test fun withoutQuestionsKeepsOnlyAnswers() {
        assertTrue(ChatExport.pickMessages(msgs, ImageOptions(questions = false)).all { it.role == "assistant" })
        assertEquals(1, ChatExport.pickMessages(msgs, ImageOptions(lastTurn = true, questions = false)).size)
    }

    /** 每种样子都画得出来；卡片款四周多出一圈边。SHOTS_DIR 设了就存成 PNG 看。 */
    @Test fun everyLookRenders() {
        val ctx = RuntimeEnvironment.getApplication()
        val conv = Conversation(title = "10 月花销表", messages = msgs)
        Look.entries.forEach { look ->
            val bmp = ChatExport.renderImage(ctx, conv, ImageOptions(look = look))
            assertEquals(1080, bmp.width)
            System.getenv("SHOTS_DIR")?.let { d ->
                java.io.File(d).mkdirs()
                java.io.File(d, "share-${look.name.lowercase()}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bmp.recycle()
        }
    }
}
