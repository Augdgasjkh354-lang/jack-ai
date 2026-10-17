package com.jack.ai.tools

import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Table
import com.jack.ai.data.Tables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** 表格技能：建表、加行、查询汇总、改行、删行、删表。行号从 1 开始，是表里现在的位置。 */

private fun num(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

private fun ToolSession.table(name: String): Table =
    Tables.get(app, name) ?: throw IllegalArgumentException(
        "没有「$name」这张表。现有的表：" + Tables.list(app).joinToString("、") { it.name }.ifBlank { "（还没有）" })

private fun JsonObject.toRow(t: Table): Pair<Table, List<String>> {
    // 没见过的列自动加到表的最后
    val extra = keys.filter { it !in t.columns }
    val cols = t.columns + extra
    val rows = if (extra.isEmpty()) t.rows else t.rows.map { it + List(extra.size) { "" } }
    return t.copy(columns = cols, rows = rows) to cols.map { c -> this[c].text() }
}

internal suspend fun ToolSession.listTables() = runTool("查看表格") {
    val list = withContext(Dispatchers.IO) { Tables.list(app) }
    (if (list.isEmpty()) "还没有表格。" else list.joinToString("\n") { "「${it.name}」${it.rows.size} 行｜列：${it.columns.joinToString("、")}" }) to
        SearchRecord("查看表格")
}

internal suspend fun ToolSession.createTable(args: JsonObject): String {
    val name = Tables.safeName(args["name"].text())
    val cols = (args["columns"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
    return runTool("建表：$name") {
        if (name.isBlank() || cols.isEmpty()) throw IllegalArgumentException("要有表名和至少一列")
        withContext(Dispatchers.IO) {
            if (Tables.get(app, name) != null) throw IllegalArgumentException("「$name」已经有了，直接往里加就行")
            Tables.save(app, Table(name, cols, emptyList()))
        }
        "已建好「$name」，列：${cols.joinToString("、")}" to SearchRecord("建表：$name")
    }
}

internal suspend fun ToolSession.addRows(args: JsonObject): String {
    val name = args["name"].text()
    val items = (args["rows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return runTool("往「$name」加 ${items.size} 行") {
        if (items.isEmpty()) throw IllegalArgumentException("没有要加的行")
        val total = withContext(Dispatchers.IO) {
            var t = table(name)
            items.forEach { o ->
                val (t2, row) = o.toRow(t)
                t = t2.copy(rows = t2.rows + listOf(row))
            }
            Tables.save(app, t)
            t.rows.size
        }
        "已加 ${items.size} 行，「$name」现在共 $total 行。" to SearchRecord("「$name」+${items.size} 行")
    }
}

internal suspend fun ToolSession.queryTable(args: JsonObject): String {
    val name = args["name"].text()
    val where = (args["where"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        Tables.Where(o["column"].text(), o["op"].text().ifBlank { "=" }, o["value"].text())
    }
    val sumCols = (args["sum"] as? JsonArray).orEmpty().map { it.text() }.filter { it.isNotBlank() }
    val limit = (args["limit"].text().toIntOrNull() ?: 50).coerceIn(1, 500)
    return runTool("查「$name」") {
        val t = withContext(Dispatchers.IO) { table(name) }
        val bad = (where.map { it.column } + sumCols).filter { it !in t.columns }
        if (bad.isNotEmpty()) throw IllegalArgumentException("没有这些列：${bad.joinToString("、")}。现有列：${t.columns.joinToString("、")}")
        val hits = t.rows.withIndex().filter { (_, r) -> Tables.matches(t, r, where) }
        val text = buildString {
            append("「$name」符合条件的共 ${hits.size} 行")
            if (hits.size > limit) append("，下面是最后 $limit 行")
            append("。\n行号,").append(t.columns.joinToString(",")).append("\n")
            hits.takeLast(limit).forEach { (i, r) -> append(i + 1).append(',').append(r.joinToString(",")).append("\n") }
            sumCols.forEach { c ->
                val ci = t.columns.indexOf(c)
                val vals = hits.mapNotNull { it.value[ci].replace(",", "").replace("¥", "").replace("元", "").trim().toDoubleOrNull() }
                append("\n「$c」合计 ${num(vals.sum())}（${vals.size} 个数字）")
            }
        }
        text to SearchRecord("查「$name」：${hits.size} 行")
    }
}

private fun JsonObject.rowNumbers(): List<Int> = (this["rows"] as? JsonArray).orEmpty().mapNotNull { it.text().toIntOrNull() }.distinct()

internal suspend fun ToolSession.updateRows(args: JsonObject): String {
    val name = args["name"].text()
    val nums = args.rowNumbers()
    val set = args["set"] as? JsonObject
    return runTool("改「$name」") {
        if (nums.isEmpty() || set == null || set.isEmpty()) throw IllegalArgumentException("要有行号和要改的内容")
        withContext(Dispatchers.IO) {
            val t0 = table(name)
            nums.forEach { if (it !in 1..t0.rows.size) throw IllegalArgumentException("没有第 $it 行（共 ${t0.rows.size} 行）") }
            val (t, _) = set.toRow(t0)
            val rows = t.rows.mapIndexed { i, r ->
                if (i + 1 !in nums) r else r.mapIndexed { ci, v -> set[t.columns[ci]]?.text() ?: v }
            }
            Tables.save(app, t.copy(rows = rows))
        }
        "已改 ${nums.size} 行。" to SearchRecord("「$name」改了 ${nums.size} 行")
    }
}

internal suspend fun ToolSession.deleteRows(args: JsonObject): String {
    val name = args["name"].text()
    val nums = args.rowNumbers()
    return runTool("删「$name」的行") {
        if (nums.isEmpty()) throw IllegalArgumentException("要有行号")
        val t = withContext(Dispatchers.IO) { table(name) }
        nums.forEach { if (it !in 1..t.rows.size) throw IllegalArgumentException("没有第 $it 行（共 ${t.rows.size} 行）") }
        if (nums.size > 3) {
            val preview = nums.sorted().joinToString("\n") { "$it：" + t.rows[it - 1].joinToString("，") }
            if (!ui.confirm("从「$name」删掉 ${nums.size} 行？", preview, "删除")) return@runTool "用户取消了，没有删。" to SearchRecord("删行：已取消")
        }
        withContext(Dispatchers.IO) { Tables.save(app, t.copy(rows = t.rows.filterIndexed { i, _ -> i + 1 !in nums })) }
        "已删 ${nums.size} 行，剩 ${t.rows.size - nums.size} 行。注意行号已经变了，再操作前先重新查。" to SearchRecord("「$name」删了 ${nums.size} 行")
    }
}

internal suspend fun ToolSession.deleteTable(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("删表：$name") {
        val t = withContext(Dispatchers.IO) { table(name) }
        if (!ui.confirm("删除整张表「$name」？", "共 ${t.rows.size} 行，删了找不回来。", "删除")) return@runTool "用户取消了，没有删。" to SearchRecord("删表：已取消")
        withContext(Dispatchers.IO) { Tables.delete(app, name) }
        "已删除「$name」。" to SearchRecord("删表：$name")
    }
}
