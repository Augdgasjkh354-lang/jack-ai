package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VerifyTest {
    private val sheets = listOf(
        OfficeWriter.Sheet("明细", listOf(listOf("项目", "金额"), listOf("房租", "3000"), listOf("饭钱", "1500"), listOf("合计", "=SUM(B2:B3)"))),
        OfficeWriter.Sheet("汇总", listOf(listOf("总额", "=明细!B4"), listOf("取整", "=ROUND(LOG10(B1),2)"))),
    )

    @Test
    fun goodFormulasPass() {
        assertEquals(emptyList<String>(), Verify.xlsxFormulas(sheets))
    }

    @Test
    fun badFormulasAreCaught() {
        val bad = listOf(OfficeWriter.Sheet("表", listOf(
            listOf("a", "b"),
            listOf("1", "=SUM(B2:B9)"),
            listOf("2", "=B3"),
            listOf("3", "=其他!A1"),
            listOf("4", "=SUM(A1:A3"),
        )))
        val p = Verify.xlsxFormulas(bad)
        assertTrue(p.toString(), p.any { "B9 超出了数据范围" in it })
        assertTrue(p.toString(), p.any { "引用了自己" in it })
        assertTrue(p.toString(), p.any { "不存在的工作表「其他」" in it })
        assertTrue(p.toString(), p.any { "括号没配对" in it })
    }

    @Test
    fun textFiles() {
        assertEquals(emptyList<String>(), Verify.textFile("a.json", """{"a":1}"""))
        assertTrue(Verify.textFile("a.json", """{"a":1""").single().startsWith("「a.json」不是有效的 JSON"))
        assertTrue(Verify.textFile("a.csv", "a,b\n1,2\n3").single().contains("第 3 行"))
        assertEquals(emptyList<String>(), Verify.textFile("a.md", "随便"))
    }

    @Test
    fun filesThatDoNotOpen() {
        val dir = Files.createTempDirectory("verify").toFile()
        val empty = File(dir, "empty.docx").apply { writeBytes(ByteArray(0)) }
        val fake = File(dir, "fake.pdf").apply { writeText("hello") }
        val docx = File(dir, "good.docx").apply { outputStream().use { OfficeWriter.writeDocx("# 标题\n正文", it) } }
        assertTrue(Verify.fileOpens(empty).toString(), Verify.fileOpens(empty)!!.contains("空的"))
        assertTrue(Verify.fileOpens(fake)!!.contains("不是有效的 PDF"))
        assertNull(Verify.fileOpens(docx))
        assertTrue(Verify.note(emptyList(), "好").contains("✓"))
        assertTrue(Verify.note(listOf("坏了"), "好").startsWith("\n【核实失败】坏了"))
    }
}
