package com.jack.ai.data

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * 交付验收：工具做完事后自己再查一遍，结果附在工具返回的最后。
 * 只做能用程序确定的检查（文件能不能打开、公式引用对不对、语法有没有错），不靠模型自己说“我检查过了”。
 */
object Verify {
    private const val FAILED = "\n【核实失败】"

    /** 工具结果里有没有 note 写下的核实失败（这个标记是代码加的，不是模型写的）。 */
    fun failed(result: String) = FAILED in result

    /** 附在工具结果后面的一行。problems 为空就是通过。 */
    fun note(problems: List<String>, ok: String, fix: String = "用 edit_file 改"): String =
        if (problems.isEmpty()) "\n【核实】✓ $ok"
        else FAILED + problems.take(10).joinToString("；") + (if (problems.size > 10) "；……共 ${problems.size} 处" else "") +
            "。先修好再交付（$fix），修不好就如实告诉用户哪里有问题。"

    /** 生成的文件能不能打开：Office 是不是完整的 zip、PDF 文件头对不对、有没有内容。 */
    fun fileOpens(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "「${file.name}」是空的或没生成出来"
        return when (file.extension.lowercase()) {
            "docx", "pptx", "xlsx" -> runCatching {
                ZipFile(file).use { z ->
                    if (z.getEntry("[Content_Types].xml") == null) "「${file.name}」缺少必要的结构，打不开"
                    else null
                }
            }.getOrElse { "「${file.name}」不是完整的 Office 文件，打不开" }
            "pdf" -> file.inputStream().use { s -> val head = ByteArray(5); s.read(head); if (String(head) != "%PDF-") "「${file.name}」不是有效的 PDF" else null }
            "png" -> file.inputStream().use { s -> val head = ByteArray(4); s.read(head); if (head[1] != 'P'.code.toByte() || head[2] != 'N'.code.toByte()) "「${file.name}」不是有效的图片" else null }
            else -> null
        }
    }

    /** 单元格引用：可选的「表名!」+ 列 + 行。前后不能紧挨字母数字（排除 LOG10( 这类函数名）。 */
    private val REF = Regex("""(?:(?:'([^']+)'|([^\s!'(),:;+\-*/=<>&^"]+))!)?(?<![A-Za-z0-9_.$])\$?([A-Z]{1,3})\$?([0-9]+)(?![0-9A-Za-z_(])""")

    /**
     * 检查 Excel 公式：括号和引号配不配对、引用的工作表在不在、引用的格子在不在数据范围里、有没有引用自己。
     * 行号从表头那一行算 1，和写公式时的约定一样。
     */
    fun xlsxFormulas(sheets: List<OfficeWriter.Sheet>): List<String> {
        val byName = sheets.associateBy { it.name.trim() }
        val out = mutableListOf<String>()
        sheets.forEach { sheet ->
            sheet.rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, v ->
                    if (!v.startsWith("=")) return@forEachIndexed
                    val at = "「${sheet.name}」${colName(c)}${r + 1}"
                    val f = v.substring(1)
                    if (f.count { it == '"' } % 2 != 0) { out += "$at 的公式引号没配对"; return@forEachIndexed }
                    val bare = f.replace(Regex("\"[^\"]*\""), "\"\"")
                    if (bare.count { it == '(' } != bare.count { it == ')' }) { out += "$at 的公式括号没配对：$v"; return@forEachIndexed }
                    REF.findAll(bare).forEach { m ->
                        val sheetRef = m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotEmpty() }
                        val target = if (sheetRef != null) byName[sheetRef.trim()] else sheet
                        if (sheetRef != null && target == null) { out += "$at 引用了不存在的工作表「$sheetRef」"; return@forEach }
                        val col = colIndex(m.groupValues[3])
                        val row = m.groupValues[4].toIntOrNull() ?: return@forEach
                        val t = target ?: return@forEach
                        val width = t.rows.maxOfOrNull { it.size } ?: 0
                        if (row > t.rows.size || col >= width) out += "$at 引用的 ${m.groupValues[3]}$row 超出了数据范围（${t.name} 只有 ${t.rows.size} 行、$width 列）"
                        else if (t === sheet && row == r + 1 && col == c) out += "$at 的公式引用了自己（循环引用）"
                    }
                }
            }
        }
        return out.distinct()
    }

    /** 小工具里能加载代码的网站（常用的前端库 CDN），别的联网一律拦掉。 */
    val APP_HOSTS = setOf("cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com", "cdn.tailwindcss.com", "fonts.googleapis.com", "fonts.gstatic.com")

    /** 小工具网页的静态检查：有没有内容、script 标签配不配对、有没有引用会被拦截的外部地址。 */
    fun html(text: String): List<String> {
        val out = mutableListOf<String>()
        if (text.isBlank()) return listOf("网页是空的")
        val lower = text.lowercase()
        if ("<body" !in lower && "<div" !in lower && "<script" !in lower && "<input" !in lower) out += "网页里没有可显示的内容"
        if (Regex("<script\\b").findAll(lower).count() != Regex("</script>").findAll(lower).count()) out += "<script> 标签没配对"
        Regex("""(?:src|href)\s*=\s*["'](https?://[^"'/]+)""", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }
            .map { it.substringAfter("://").lowercase() }.distinct()
            .filter { it !in APP_HOSTS }
            .forEach { out += "引用了 $it 上的资源，会被拦截（只能用 ${APP_HOSTS.take(3).joinToString("、")} 等 CDN，或者把代码直接写进网页）" }
        return out
    }

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun colIndex(s: String): Int = s.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    /** 文字文件的格式检查：JSON 能不能解析，CSV 每行列数是否一致。其他类型返回空。 */
    fun textFile(name: String, text: String): List<String> = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> runCatching { Json.parseToJsonElement(text); emptyList<String>() }
            .getOrElse { listOf("「$name」不是有效的 JSON：${it.message?.lineSequence()?.firstOrNull().orEmpty()}") }
        "csv" -> {
            val rows = Tables.parseCsv(text.removePrefix("﻿")).filter { r -> r.any { it.isNotBlank() } }
            val width = rows.firstOrNull()?.size ?: 0
            val bad = rows.withIndex().filter { it.value.size != width }.map { it.index + 1 }
            if (bad.isEmpty()) emptyList() else listOf("「$name」第 ${bad.take(5).joinToString("、")} 行的列数和表头（$width 列）不一样")
        }
        else -> emptyList()
    }
}
