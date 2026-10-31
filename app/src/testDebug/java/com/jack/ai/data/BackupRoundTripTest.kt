package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 换签名要卸载重装，全靠备份：后来加的事实库、我的模板、网站备忘这些也要能带过去。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun newerStoresSurviveExportAndImport() {
        val dir = ctx.filesDir
        Store(ctx).saveConversations(listOf(Conversation(title = "t")))
        val files = mapOf(
            "facts/facts.json" to "[]",
            "style_templates.json" to "[{\"name\":\"公司模板\"}]",
            "site_notes.json" to "[]",
            "recipients.json" to "[\"a@b.com\"]",
            "memories_versions/1.json" to "[]",
        )
        files.forEach { (n, t) -> File(dir, n).apply { parentFile?.mkdirs(); writeText(t) } }
        val out = ByteArrayOutputStream()
        Backup.export(ctx, out)
        files.keys.forEach { File(dir, it).delete() }
        Backup.import(ctx, ByteArrayInputStream(out.toByteArray()))
        files.forEach { (n, t) -> assertTrue(n, File(dir, n).exists()); assertEquals(n, t, File(dir, n).readText()) }
    }
}
