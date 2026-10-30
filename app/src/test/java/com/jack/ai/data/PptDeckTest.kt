package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 新版 PPT：每种版式都能画、每页 XML 合法，转场和动画写进去了，图标是矢量的，字多了有提醒。
 * 顺手把样例写到 build/office-samples/showcase.pptx，可以拿 LibreOffice 渲染出来看。
 */
class PptDeckTest {
    private fun png(w: Int, h: Int): ByteArray {
        fun chunk(out: java.io.DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size); val td = type.toByteArray() + data; out.write(td)
            out.writeInt(java.util.zip.CRC32().apply { update(td) }.value.toInt())
        }
        val raw = ByteArrayOutputStream()
        repeat(h) { y -> raw.write(0); repeat(w) { x -> raw.write(40 + x * 120 / w); raw.write(90 + y * 100 / h); raw.write(160) } }
        val bos = ByteArrayOutputStream(); val out = java.io.DataOutputStream(bos)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", ByteArrayOutputStream().also { b -> java.io.DataOutputStream(b).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
        val z = ByteArrayOutputStream(); java.util.zip.DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return bos.toByteArray()
    }

    private fun slide(layout: String, title: String, vararg bullets: String, icons: List<String> = emptyList(), image: Boolean = false, transition: String = "") =
        OfficeWriter.Slide(layout, title, bullets.toList(), image = if (image) png(320, 200) else null, imageExt = "png",
            imageW = if (image) 320 else 0, imageH = if (image) 200 else 0, icons = icons, transition = transition)

    private val deck = listOf(
        slide("title", "2026 年度产品战略", "汇报人 Jack · 2026-10-30", "产品部"),
        slide("agenda", "目录", "市场回顾：今年发生了什么", "核心成果：我们做到了什么", "明年规划：要去哪里", "资源需求：需要什么支持", "风险与对策"),
        slide("section", "市场回顾", "行业整体增速放缓，但细分赛道机会仍在"),
        slide("stats", "今年的成绩单", "32%：营收同比增长", "1.2 亿：累计用户", "Top 3：细分市场排名", icons = listOf("trending-up", "users", "trophy")),
        slide("cards", "我们的三个优势", "技术领先：自研引擎，响应速度比同行快一倍", "用户口碑：NPS 连续四个季度超过 60", "成本控制：单用户获客成本下降 18%",
            icons = listOf("cpu", "heart", "piggy-bank")),
        slide("content", "下半年重点工作", "增长：渠道下沉到三四线城市", "产品：上线 AI 助手功能", "  先在两个城市试点", "组织：组建数据中台团队", image = true, icons = listOf("rocket")),
        slide("two_column", "方案对比", "## 方案 A：自建", "前期投入大", "长期成本低", "完全可控", "## 方案 B：外采", "上线快", "按量付费", "受制于供应商", icons = listOf("hammer", "shopping-cart")),
        slide("timeline", "明年路线图", "Q1：完成渠道调研", "Q2：AI 助手内测", "Q3：全量上线", "Q4：复盘与扩张"),
        slide("process", "落地流程", "调研：访谈 50 位用户", "设计：出三版原型", "开发：两周一个迭代", "上线：灰度 10% 起步", "复盘：看数据定下一步"),
        slide("quote", "做产品就是做取舍，说不比说是更重要。", "乔布斯"),
        slide("image", "下一站，星辰大海", "我们一起出发", image = true, transition = "zoom"),
        slide("end", "谢谢", "jack@example.com"),
    )

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    @Test
    fun everyLayoutRendersValidXmlWithMotion() {
        val bos = ByteArrayOutputStream()
        val warnings = OfficeWriter.writePptx(deck, "blue", bos, transition = "push", animation = "rise")
        val bytes = bos.toByteArray()
        File(System.getProperty("user.dir"), "build/office-samples").apply { mkdirs() }.resolve("showcase.pptx").writeBytes(bytes)
        PptDeck.THEMES.keys.forEach { name ->
            File(System.getProperty("user.dir"), "build/office-samples/theme-$name.pptx").outputStream().use { OfficeWriter.writePptx(deck.take(6), name, it) }
        }
        val p = parts(bytes)
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        deck.indices.forEach { i ->
            val xml = p.getValue("ppt/slides/slide${i + 1}.xml")
            f.newDocumentBuilder().parse(xml.byteInputStream())  // 不合法会抛错
            // 转场跟着整份走，第 11 页自己写了 zoom
            assertTrue("slide ${i + 1}", xml.contains(if (i == 10) "<p:zoom/>" else "<p:push dir=\"u\"/>"))
            // 元素顺序：cSld、clrMapOvr、transition、timing
            assertTrue(xml.indexOf("<p:clrMapOvr>") < xml.indexOf("<p:transition"))
        }
        // 封面、章节、引言、大图、结尾不逐条出现；其余有动画
        listOf(0, 2, 9, 10, 11).forEach { assertFalse(p.getValue("ppt/slides/slide${it + 1}.xml").contains("<p:timing>")) }
        listOf(1, 3, 4, 5, 6, 7, 8).forEach { i ->
            val xml = p.getValue("ppt/slides/slide${i + 1}.xml")
            assertTrue("slide ${i + 1}", xml.contains("<p:timing>") && xml.contains("clickEffect"))
            assertTrue(xml.indexOf("<p:transition") < xml.indexOf("<p:timing>"))
        }
        // 要点页按段出现
        assertTrue(p.getValue("ppt/slides/slide6.xml").contains("build=\"p\""))
        assertTrue(p.getValue("ppt/slides/slide6.xml").contains("<p:pRg st=\"3\" end=\"3\"/>"))
        // 图标是矢量路径
        assertTrue(p.getValue("ppt/slides/slide5.xml").contains("<a:custGeom>"))
        assertTrue(p.getValue("ppt/slides/slide5.xml").contains("name=\"Icon cpu\""))
        // cTn 编号在一页里不重复
        val ids = Regex("<p:cTn id=\"(\\d+)\"").findAll(p.getValue("ppt/slides/slide5.xml")).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test
    fun noneTurnsMotionOff() {
        val bos = ByteArrayOutputStream()
        OfficeWriter.writePptx(deck, "dark", bos, transition = "none", animation = "none")
        val p = parts(bos.toByteArray())
        assertFalse(p.values.any { it.contains("<p:timing>") })
        assertFalse(p.getValue("ppt/slides/slide2.xml").contains("<p:transition"))
        assertTrue(p.getValue("ppt/slides/slide11.xml").contains("<p:zoom/>"))  // 单页写了的照样有
    }

    @Test
    fun crowdedSlidesAndUnknownIconsAreReported() {
        val long = "这是一段特别特别长的说明文字，".repeat(12)
        val warnings = OfficeWriter.writePptx(listOf(
            slide("cards", "太多卡片", *Array(8) { "第${it}张：$long" }, icons = listOf("rocket", "不存在的图标xyz")),
            slide("content", "字太多", *Array(8) { long }),
        ), "blue", ByteArrayOutputStream())
        assertTrue(warnings.toString(), warnings.any { it.contains("最多 6 个卡片") })
        assertTrue(warnings.toString(), warnings.any { it.contains("不存在的图标xyz") })
        assertTrue(warnings.toString(), warnings.any { it.contains("第 2 页") && it.contains("字太多") })
    }

    @Test
    fun iconsLoadAndNamesAreForgiving() {
        assertTrue(PptDeck.ICONS.size >= 200)
        assertEquals("rocket", PptDeck.iconName("Rocket"))
        assertEquals("house", PptDeck.iconName("home"))
        assertEquals("trending-up", PptDeck.iconName("growth"))
        assertEquals("chart-column", PptDeck.iconName("chart_column"))
        assertNull(PptDeck.iconName("不存在"))
        assertTrue(PptDeck.pathOps("M100,200 L300,400 C1,2,3,4,5,6 Z").startsWith("<a:moveTo><a:pt x=\"100\" y=\"200\"/></a:moveTo><a:lnTo>"))
    }

    @Test
    fun fitShrinksLongTextButStaysInRange() {
        assertEquals(2400, PptDeck.fit(listOf("短"), 8_000_000, 3_000_000, 2400, 1400))
        val many = List(8) { "这是一段比较长的要点文字用来测试自动缩小字号".repeat(2) }
        val s = PptDeck.fit(many, 5_000_000, 3_000_000, 2400, 1400)
        assertTrue(s in 1400 until 2400)
        assertEquals("标题" to "说明", PptDeck.split("标题：说明"))
        assertEquals("没有冒号" to "", PptDeck.split("没有冒号"))
    }
}
