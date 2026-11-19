package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageGroupTest {
    @Test
    fun designSeriesSavesAsAGroupOtherImagesAlone() {
        val dir = Files.createTempDirectory("grp").toFile()
        listOf("租房-01", "租房-02", "租房-03").forEach { File(dir, "$it.html").writeText("x"); File(dir, "$it.png").writeText("x") }
        File(dir, "照片.jpg").writeText("x")
        File(dir, "封面.html").writeText("x"); File(dir, "封面.png").writeText("x")
        assertEquals(listOf("租房-01.png", "租房-02.png", "租房-03.png"), groupOf(File(dir, "租房-02.png")).map { it.name })
        assertEquals(listOf("照片.jpg"), groupOf(File(dir, "照片.jpg")).map { it.name })
        assertEquals(listOf("封面.png"), groupOf(File(dir, "封面.png")).map { it.name })
        dir.deleteRecursively()
    }
}
