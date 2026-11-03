package com.jack.ai.data

import android.app.Application
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** 审计复现：这些测试故意失败，只用于证明持久化竞态/半写文件的后果。 */
@RunWith(RobolectricTestRunner::class)
class PersistenceAuditTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Ignore("audit: 复现")
    @Test
    fun globalMemoryStaleSnapshotsLoseOneConversationUpdate() {
        app.filesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("memories.json") }
            .forEach { it.delete() }
        val store = Store(app)
        store.saveMemories(listOf(MemoryItem(text = "原有记忆")))

        // 两个对话几乎同时回看：都在各自的 save 之前读到了同一个旧快照。
        val fromA = store.loadMemories()
        val fromB = store.loadMemories()
        store.saveMemories(fromA + MemoryItem(text = "对话 A 新记住的偏好"))
        store.saveMemories(fromB + MemoryItem(text = "对话 B 新记住的事实"))

        val texts = store.loadMemories().map { it.text }
        assertTrue("两个对话的新记忆都应该保留，实际 A 被最后一次整表保存覆盖", "对话 A 新记住的偏好" in texts)
        assertTrue("对话 B 应该保留", "对话 B 新记住的事实" in texts)
    }

    @Ignore("audit: 复现")
    @Test
    fun sharedWorkMemoryStaleSnapshotsLoseOneConversationUpdate() {
        val dir = Files.createTempDirectory("work-memory-audit").toFile()
        val file = File(dir, ChatMemory.WORK_FILE)
        ChatMemory.write(file, listOf("原有做事经验"))

        // 两个 Work 对话都先读，再各自整份写回共享文件。
        val fromA = ChatMemory.items(ChatMemory.read(file))
        val fromB = ChatMemory.items(ChatMemory.read(file))
        ChatMemory.write(file, fromA + "对话 A 学到的交付习惯")
        ChatMemory.write(file, fromB + "对话 B 学到的工具经验")

        val items = ChatMemory.items(ChatMemory.read(file))
        assertTrue("共享做事经验不应丢掉 A 的更新", "对话 A 学到的交付习惯" in items)
        assertTrue("B 的更新应该保留", "对话 B 学到的工具经验" in items)
    }

    @Test
    fun halfWrittenStyleTemplatesSilentlyLoadAsEmpty() {
        val file = File(app.filesDir, "style_templates.json")
        val template = StyleTemplates.Template("公司汇报", "ppt")
        StyleTemplates.save(app, listOf(template))
        val full = file.readText()

        // 模拟 AtomicFile 新版本只写到一半，App 就被杀：没有 finishWrite / failWrite。
        AtomicFile(file).startWrite().use { it.write(full.take(full.length / 2).toByteArray()) }

        val loaded = StyleTemplates.load(app)
        assertTrue("半写文件不应让用户已有模板静默变成空列表", loaded.any { it.name == "公司汇报" })
    }
}
