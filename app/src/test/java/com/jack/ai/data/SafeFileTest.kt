package com.jack.ai.data

import android.util.AtomicFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SafeFileTest {
    @Test
    fun readsLegacyPlainFile() {
        val file = File(Files.createTempDirectory("safe-file-legacy").toFile(), "legacy.txt")
        file.writeText("旧格式文件")

        assertEquals("旧格式文件", SafeFile.readText(file))
    }

    @Test
    fun writesAndReadsText() {
        val file = File(Files.createTempDirectory("safe-file-roundtrip").toFile(), "nested/data.txt")

        SafeFile.writeText(file, "第一行\n第二行")

        assertEquals("第一行\n第二行", SafeFile.readText(file))
    }

    @Test
    fun failedAtomicWriteKeepsPreviousText() {
        val file = File(Files.createTempDirectory("safe-file-failed").toFile(), "data.txt")
        SafeFile.writeText(file, "原内容")
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        out.write("半截新内容".toByteArray())
        atomic.failWrite(out)

        assertEquals("原内容", SafeFile.readText(file))
    }
}
