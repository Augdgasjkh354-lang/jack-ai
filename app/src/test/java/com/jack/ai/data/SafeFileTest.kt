package com.jack.ai.data

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
}
