package com.jack.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 别的 App 递进来的地址：Kavi 自己的私有文件不收，别人的照收。 */
class ShareGuardTest {
    private val data = File(System.getProperty("java.io.tmpdir"), "share-guard/data/com.jack.ai").apply { mkdirs() }
    private fun ok(scheme: String?, authority: String?, path: String?) = ShareGuard.outside(scheme, authority, path, "com.jack.ai", listOf(data))

    @Test
    fun othersContentIsAccepted() {
        assertTrue(ok("content", "com.tencent.mm.external.fileprovider", "/x/1.jpg"))
        assertTrue(ok("content", "media", "/external/images/1"))
    }

    @Test
    fun ownPrivateFilesAreRefused() {
        assertFalse(ok("content", "com.jack.ai.files", "/workspaces/a/x.txt"))
        assertFalse(ok("content", "com.jack.ai", "/x"))
        assertFalse(ok("file", null, File(data, "shared_prefs/s.xml").path))
        // 用 ../ 绕回数据目录也不行
        assertFalse(ok("file", null, File(data, "cache/../files/conversations/a.json").path))
        assertFalse(ok(null, null, "/x"))
    }

    @Test
    fun fileOutsideIsAccepted() {
        assertTrue(ok("file", null, "/sdcard/Download/a.pdf"))
        // 名字像但不是同一个目录
        assertTrue(ok("file", null, data.path + "2/x"))
        assertTrue(ok("content", "com.jack.aix.files", "/x"))
    }
}
