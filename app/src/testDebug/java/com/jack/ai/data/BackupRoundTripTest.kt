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
            // 以前手写清单漏掉的
            "workflows.json" to "[]",
            "skill_files/abc/ref/notes.md" to "资料",
            "readlater/1a.md" to "稍后读",
        )
        // 不该带走的：沙箱、断点、向量、临时文件
        val skipped = listOf("sandbox/rootfs/bin/sh", "checkpoints/w1.json", "memory_vectors.bin", "projects.json.prev", "workspaces/c1/.cache/t.png")
        skipped.forEach { File(dir, it).apply { parentFile?.mkdirs(); writeText("x") } }
        files.forEach { (n, t) -> File(dir, n).apply { parentFile?.mkdirs(); writeText(t) } }
        val out = ByteArrayOutputStream()
        Backup.export(ctx, out)
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        skipped.forEach { assertTrue("不该备份 $it", it !in names) }
        files.keys.forEach { File(dir, it).delete() }
        Backup.import(ctx, ByteArrayInputStream(out.toByteArray()))
        files.forEach { (n, t) -> assertTrue(n, File(dir, n).exists()); assertEquals(n, t, File(dir, n).readText()) }
    }
}
