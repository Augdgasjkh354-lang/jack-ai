package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 对话从「全挤在一个文件」拆成「一个对话一个文件」：旧数据要原样搬过来，中途出事不能丢，备份格式不变。 */
@RunWith(RobolectricTestRunner::class)
class ConversationStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val legacy get() = File(app.filesDir, "conversations.json")
    private val kept get() = File(app.filesDir, "conversations.json.migrated")
    private val convDir get() = File(app.filesDir, "conversations")

    private fun conv(title: String, at: Long, id: String = newId()) = Conversation(
        id = id, title = title, updatedAt = at,
        messages = listOf(ChatMessage(role = "user", content = "你好 $title"), ChatMessage(role = "assistant", content = "在。")),
    )

    private fun writeLegacy(list: List<Conversation>) =
        legacy.writeText(json.encodeToString(ListSerializer(Conversation.serializer()), list))

    private fun files() = convDir.list().orEmpty().filter { it.endsWith(".json") }.sorted()

    @Before fun clean() {
        legacy.delete(); kept.delete(); convDir.deleteRecursively()
        File(app.filesDir, "conversations.json.prev").delete()
        Store.recovered.clear()
    }

    @Test fun oldSingleFileIsSplitWithNothingLost() {
        val old = listOf(conv("甲", 3), conv("乙", 2), conv("丙", 1))
        writeLegacy(old)
        File(app.filesDir, "conversations.json.prev").writeText("[]")

        assertEquals(old, Store(app).loadConversations())
        assertEquals(3, files().size)
        assertFalse("旧文件拆完要改名，不然每次打开都重拆", legacy.exists())
        assertTrue("旧文件留一份备查", kept.exists())
        assertFalse(File(app.filesDir, "conversations.json.prev").exists())
        // 再开一次：读的是拆好的文件
        assertEquals(old, Store(app).loadConversations())
        assertTrue(Store.recovered.isEmpty())
    }

    @Test fun migrationKilledHalfwayStartsOverFromTheOldFile() {
        val old = listOf(conv("甲", 3), conv("乙", 2), conv("丙", 1))
        writeLegacy(old)
        // 上次拆到一半被杀：只写出了一个，旧文件还没改名
        convDir.mkdirs()
        File(convDir, "${old[0].id}.json").writeText(json.encodeToString(Conversation.serializer(), old[0]).dropLast(20))

        assertEquals(old, Store(app).loadConversations())
        assertTrue(Store.recovered.isEmpty())
    }

    @Test fun corruptOldFileWithoutCopyLeavesSplitConversationsAlone() {
        val a = conv("甲", 1)
        Store(app).saveConversations(listOf(a))
        legacy.writeText("[{\"id\":")

        assertEquals(listOf(a), Store(app).loadConversations())
        assertTrue(Store.recovered.any { it == "conversations.json:empty" })
        assertTrue("坏文件改名留着", app.filesDir.list().orEmpty().any { it.startsWith("conversations.json.corrupt-") })
    }

    @Test fun savingRewritesOnlyTheConversationThatChanged() {
        val a = conv("甲", 3); val b = conv("乙", 2); val c = conv("丙", 1)
        val store = Store(app)
        store.saveConversations(listOf(a, b, c))
        val past = 1_600_000_000_000L
        convDir.listFiles()!!.forEach { it.setLastModified(past) }

        val b2 = b.copy(title = "乙改", messages = b.messages + ChatMessage(role = "user", content = "再问一句"))
        store.saveConversations(listOf(a, b2, c))

        assertNotEquals(past, File(convDir, "${b.id}.json").lastModified())
        assertEquals(past, File(convDir, "${a.id}.json").lastModified())
        assertEquals(past, File(convDir, "${c.id}.json").lastModified())
        assertEquals(listOf(a, b2, c), Store(app).loadConversations())
    }

    @Test fun conversationsMissingFromTheSavedListAreDeleted() {
        val a = conv("甲", 2); val b = conv("乙", 1)
        val store = Store(app)
        store.saveConversations(listOf(a, b))
        store.saveConversations(listOf(a))

        assertEquals(listOf("${a.id}.json"), files())
        assertEquals(listOf(a), Store(app).loadConversations())
        store.saveConversations(emptyList())
        assertTrue(Store(app).loadConversations().isEmpty())
    }

    @Test fun oneCorruptConversationDoesNotTakeTheOthersDown() {
        val a = conv("甲", 2); val b = conv("乙", 1)
        Store(app).saveConversations(listOf(a, b))
        File(convDir, "${b.id}.json").writeText("{\"id\":\"${b.id}\",\"messages\":[")

        assertEquals(listOf(a), Store(app).loadConversations())
        assertTrue(Store.recovered.any { it.endsWith("${b.id}.json:empty") })
        assertTrue(convDir.list()!!.any { it.startsWith("${b.id}.json.corrupt-") })
    }

    @Test fun corruptConversationFallsBackToItsCopy() {
        val a = conv("甲", 1)
        Store(app).saveConversations(listOf(a))
        File(convDir, "${a.id}.json.prev").writeText(json.encodeToString(Conversation.serializer(), a))
        File(convDir, "${a.id}.json").writeText("坏了")

        assertEquals(listOf(a), Store(app).loadConversations())
        assertTrue(Store.recovered.any { it.endsWith("${a.id}.json:prev") })
    }

    @Test fun changesMadeBehindTheStoreAreNoticed() {
        val a = conv("甲", 1)
        val store = Store(app)
        store.saveConversations(listOf(a))
        val changed = a.copy(title = "从别处改过的标题")
        File(convDir, "${a.id}.json").writeText(json.encodeToString(Conversation.serializer(), changed))

        assertEquals(listOf(changed), store.loadConversations())
    }

    @Test fun unusualIdsGetSafeFileNames() {
        val list = listOf(conv("浏览器", 3, "browser-chat"), conv("怪编号", 2, "../a/b:c 中文"), conv("很长", 1, "长".repeat(200)))
        Store(app).saveConversations(list)

        assertEquals(list, Store(app).loadConversations())
        assertEquals(3, files().size)
        assertTrue(files().all { Regex("[A-Za-z0-9_-]+\\.json").matches(it) })
        assertEquals("对话文件都在对话目录里", 3, convDir.walkTopDown().count { it.isFile })
    }

    @Test fun oldWorkConversationsLoadAsNormalOnes() {
        writeLegacy(listOf(conv("旧 Work", 1).copy(work = true)))
        assertFalse(Store(app).loadConversations().single().work)
        assertFalse(Store(app).loadConversations().single().work)
    }

    @Test fun keptOldFileIsRemovedAfterAWeek() {
        writeLegacy(listOf(conv("甲", 1)))
        Store(app).loadConversations()
        assertTrue(kept.exists())
        Store(app).loadConversations()
        assertTrue("没到期不删", kept.exists())
        kept.setLastModified(System.currentTimeMillis() - 8 * 86_400_000L)
        Store(app).loadConversations()
        assertFalse(kept.exists())
        assertEquals(1, Store(app).loadConversations().size)
    }

    @Test fun backupStillCarriesOneConversationsJsonAndRestoreReplaces() {
        val a = conv("甲", 2); val b = conv("乙", 1)
        Store(app).saveConversations(listOf(a, b))
        val zip = ByteArrayOutputStream().also { Backup.export(app, it) }.toByteArray()

        // 备份里是旧格式的一整个数组，旧版本也读得了
        var packed: List<Conversation>? = null
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "conversations.json") packed = json.decodeFromString(ListSerializer(Conversation.serializer()), z.readBytes().decodeToString())
                assertFalse("备份里不该有拆开的对话文件", e.name.startsWith("conversations/"))
            }
        }
        assertEquals(listOf(a, b), packed)

        // 恢复：手机上后来的对话被备份里的整套换掉
        val later = conv("恢复前新聊的", 9)
        Store(app).saveConversations(listOf(later))
        Backup.import(app, ByteArrayInputStream(zip))
        assertEquals(listOf(a, b), Store(app).loadConversations())

        // 反悔：回到恢复前
        Backup.restoreRollback(app)
        assertEquals(listOf(later), Store(app).loadConversations())
    }

    @Test fun oldBackupWithSingleFileRestoresIntoSplitFiles() {
        val a = conv("甲", 2); val b = conv("乙", 1)
        writeLegacy(listOf(a, b))
        // 旧版本导出的备份：conversations.json 直接是手机上那个文件
        val zip = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("conversations.json")); z.write(legacy.readBytes()); z.closeEntry()
            }
        }.toByteArray()
        clean()
        Store(app).saveConversations(listOf(conv("别的", 5)))

        Backup.import(app, ByteArrayInputStream(zip))
        assertEquals(listOf(a, b), Store(app).loadConversations())
        assertEquals(2, files().size)
    }
}
