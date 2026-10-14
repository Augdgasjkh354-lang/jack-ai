package com.jack.ai.data

import org.junit.Test
import java.io.File

/** 生成样例文件，CI 里用 python-docx / python-pptx / openpyxl 再检查一遍能不能打开。 */
class OfficeWriterTest {
    private fun samplePng(): ByteArray {
        val w = 160; val h = 90
        fun chunk(out: java.io.DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val td = type.toByteArray() + data
            out.write(td)
            out.writeInt(java.util.zip.CRC32().apply { update(td) }.value.toInt())
        }
        val raw = java.io.ByteArrayOutputStream()
        repeat(h) { raw.write(0); repeat(w) { raw.write(0xC4); raw.write(0x67); raw.write(0x3F) } }
        val bos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bos)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        val z = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return bos.toByteArray()
    }

    @Test
    fun writeSamples() {
        val out = File(System.getProperty("user.dir"), "build/office-samples")

    out.mkdirs()
    File(out, "t.docx").outputStream().use { OfficeWriter.writeDocx("""# 2026年第三季度工作总结
开头一段，**加粗**和*斜体*以及`代码`，还有 <特殊> & 字符。

## 一、主要成果
- 第一点
- 第二点
1. 编号一
2. 编号二

### 小节
> 引用一段

| 项目 | 金额 |
|---|---|
| 房租 | 3000 |
| 饭钱 | 1500 |

```
code block
```
""", it) }
    File(out, "t.pptx").outputStream().use { OfficeWriter.writePptx(listOf(
        OfficeWriter.Slide("title", "读书分享：《活着》", listOf("汇报人 Jack · 2026-10-14")),
        OfficeWriter.Slide("section", "第一部分 作者", emptyList()),
        OfficeWriter.Slide("content", "销售额同比增长 32%", listOf("要点一", "  二级要点", "要点 <三> & 四")),
        OfficeWriter.Slide("content", "带配图的一页", listOf("左边是文字", "右边是图片"), image = samplePng(), imageExt = "png", imageW = 160, imageH = 90),
    ), "dark", it) }
    File(out, "t.xlsx").outputStream().use { OfficeWriter.writeXlsx(listOf(
        OfficeWriter.Sheet("明细", listOf(listOf("项目","金额（元）","数量","小计"), listOf("苹果","3.5","10","=B2*C2"), listOf("编号","007","1","=B3*C3"))),
        OfficeWriter.Sheet("汇总/总计", listOf(listOf("合计"), listOf("=SUM(明细!D2:D3)"))),
    ), it) }
    }
}
