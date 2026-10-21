package com.jack.ai.data

import android.content.Context
import java.io.File

/** 一张表：表头和每一行（每行和表头一样长）。 */
data class Table(val name: String, val columns: List<String>, val rows: List<List<String>>)

/**
 * 表格技能的数据：手机上 tables 文件夹里的 CSV（UTF-8 带 BOM，Excel 直接打开不乱码），所有对话共用。
 * 适合记账、清单、记录这类零散数据。
 */
object Tables {
    private val lock = Any()
    fun dir(ctx: Context) = File(ctx.filesDir, "tables").apply { mkdirs() }

    /** 表名只留安全字符，当文件名用。 */
    fun safeName(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(60)

    fun file(ctx: Context, name: String) = File(dir(ctx), safeName(name) + ".csv")

    fun list(ctx: Context): List<Table> = synchronized(lock) {
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }.mapNotNull { read(it) }
    }

    fun get(ctx: Context, name: String): Table? = synchronized(lock) { file(ctx, name).takeIf { it.exists() }?.let { read(it) } }

    fun save(ctx: Context, t: Table) = synchronized(lock) {
        val f = file(ctx, t.name)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText("﻿" + toCsv(t))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        com.jack.ai.Widgets.refreshAll(ctx)
    }

    fun delete(ctx: Context, name: String) = synchronized(lock) { file(ctx, name).delete() }

    private fun read(f: File): Table? = runCatching {
        val lines = parseCsv(f.readText().removePrefix("﻿")).map { r -> r.map(::unguard) }
        val cols = lines.firstOrNull().orEmpty()
        Table(f.nameWithoutExtension, cols, lines.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r -> List(cols.size) { r.getOrElse(it) { "" } } })
    }.getOrNull()

    fun toCsv(t: Table): String = buildString {
        (listOf(t.columns) + t.rows).forEach { r -> append(r.joinToString(",") { cell(it) }).append("\r\n") }
    }

    private fun cell(raw: String): String {
        val s = guard(raw)
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /**
     * 以 = + - @ 开头的文字，Excel 打开 CSV 时会当成公式执行（内容可能来自邮件、网页）。
     * 存的时候前面加个 ' 让 Excel 当文字看，读回来再去掉；负数之类的数字照常存。
     */
    private fun guard(s: String) = if (s.isNotEmpty() && s[0] in "=+-@" && s.toDoubleOrNull() == null) "'$s" else s

    private fun unguard(s: String) = if (s.length > 1 && s[0] == '\'' && s[1] in "=+-@" && s.drop(1).toDoubleOrNull() == null) s.drop(1) else s

    private val DATE = Regex("""^\s*(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    /** 「2026-10-5」「2026/10/05」「2026年10月5日 12:00」都认成 2026-10-05；不是日期返回 null。 */
    fun normDate(s: String): String? {
        val m = DATE.find(s) ?: return null
        val (y, mo, d) = m.destructured
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
        return String.format(java.util.Locale.US, "%s-%02d-%02d", y, mo.toInt(), d.toInt())
    }

    /** 简单的 CSV 解析：支持引号、引号里的逗号和换行。 */
    fun parseCsv(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                } else sb.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += sb.toString(); sb.clear() }
                '\r' -> {}
                '\n' -> { row += sb.toString(); sb.clear(); out += row; row = mutableListOf() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row += sb.toString(); out += row }
        return out
    }

    /** 一个筛选条件：列、比较方式（= != contains > < >= <=）、值。数字按数字比，其他按文字比。 */
    data class Where(val column: String, val op: String, val value: String)

    fun matches(t: Table, row: List<String>, where: List<Where>): Boolean = where.all { w ->
        val i = t.columns.indexOf(w.column)
        if (i < 0) return@all false
        val raw = row[i]
        // 日期写法不统一（2026-10-5 和 2026-10-05）时按日期比，不按文字比
        val da = normDate(raw)
        val db = normDate(w.value)
        val v = if (da != null && db != null && w.op != "contains") da else raw
        val wv = if (da != null && db != null && w.op != "contains") db else w.value
        val a = v.toDoubleOrNull()
        val b = wv.toDoubleOrNull()
        val cmp = if (a != null && b != null) a.compareTo(b) else v.compareTo(wv)
        when (w.op) {
            "=", "==" -> if (a != null && b != null) a == b else v == wv
            "!=" -> if (a != null && b != null) a != b else v != wv
            "contains" -> v.contains(w.value, ignoreCase = true)
            ">" -> cmp > 0
            "<" -> cmp < 0
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            else -> false
        }
    }
}
