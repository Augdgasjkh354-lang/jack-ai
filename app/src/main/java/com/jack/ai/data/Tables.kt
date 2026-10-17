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
    }

    fun delete(ctx: Context, name: String) = synchronized(lock) { file(ctx, name).delete() }

    private fun read(f: File): Table? = runCatching {
        val lines = parseCsv(f.readText().removePrefix("﻿"))
        val cols = lines.firstOrNull().orEmpty()
        Table(f.nameWithoutExtension, cols, lines.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r -> List(cols.size) { r.getOrElse(it) { "" } } })
    }.getOrNull()

    fun toCsv(t: Table): String = buildString {
        (listOf(t.columns) + t.rows).forEach { r -> append(r.joinToString(",") { cell(it) }).append("\r\n") }
    }

    private fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

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
        val v = row[i]
        val a = v.toDoubleOrNull()
        val b = w.value.toDoubleOrNull()
        val cmp = if (a != null && b != null) a.compareTo(b) else v.compareTo(w.value)
        when (w.op) {
            "=", "==" -> if (a != null && b != null) a == b else v == w.value
            "!=" -> if (a != null && b != null) a != b else v != w.value
            "contains" -> v.contains(w.value, ignoreCase = true)
            ">" -> cmp > 0
            "<" -> cmp < 0
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            else -> false
        }
    }
}
