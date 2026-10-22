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
        var created = false
        val total = withContext(Dispatchers.IO) {
            // 表不存在就按第一行的列名新建，省掉单独建表这一步
            var t = Tables.get(app, name) ?: run {
                val safe = Tables.safeName(name)
                if (safe.isBlank()) throw IllegalArgumentException("要有表名")
                created = true
                Table(safe, emptyList(), emptyList())
            }
            items.forEach { o ->
                val (t2, row) = o.toRow(t)
                t = t2.copy(rows = t2.rows + listOf(row))
            }
            Tables.save(app, t)
            // 存完重新读，行数对得上才算写进去了
            val back = Tables.get(app, t.name)?.rows?.size
            val expected = t.rows.count { r -> r.any { it.isNotBlank() } }
            if (back != expected) throw IllegalStateException("写入后重新读表，行数对不上（应为 $expected，实际 ${back ?: 0}），可能没存成功")
            t.rows.size
        }
        (if (created) "新建了表「$name」，" else "") + "已加 ${items.size} 行，「$name」现在共 $total 行。" to
            SearchRecord("「$name」+${items.size} 行")
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
    val orderBy = args["order_by"].text().trim()
    val desc = args["desc"].text() == "true"
    val groupBy = args["group_by"].text().trim()
    val period = args["period"].text().trim()
    return runTool("查「$name」") {
        val t = withContext(Dispatchers.IO) { table(name) }
        val bad = (where.map { it.column } + sumCols + listOf(orderBy, groupBy).filter { it.isNotEmpty() }).filter { it !in t.columns }
        if (bad.isNotEmpty()) throw IllegalArgumentException("没有这些列：${bad.joinToString("、")}。现有列：${t.columns.joinToString("、")}")
        val hits = t.rows.withIndex().filter { (_, r) -> Tables.matches(t, r, where) }
        val text = buildString {
            append("「$name」符合条件的共 ${hits.size} 行")
            if (groupBy.isNotEmpty()) {
                // 分组汇总：按日期分组时按时间排，其他按第一个求和列从大到小（没有求和列就按行数）
                val groups = Tables.group(t, hits.map { it.value }, groupBy, period, sumCols).let { gs ->
                    when {
                        orderBy == groupBy || period in setOf("day", "month", "year") ->
                            gs.sortedWith { a, b -> Tables.compareCells(a.key, b.key) }.let { if (desc) it.reversed() else it }
                        sumCols.isNotEmpty() -> gs.sortedByDescending { it.sums.getValue(sumCols.first()) }
                        else -> gs.sortedByDescending { it.count }
                    }
                }
                val by = groupBy + when (period) { "year" -> "（按年）"; "month" -> "（按月）"; "day" -> "（按天）"; else -> "" }
                append("，按「$by」分成 ${groups.size} 组：\n$groupBy,行数").append(sumCols.joinToString("") { ",${it}合计" }).append("\n")
                groups.take(limit).forEach { g -> append(g.key).append(',').append(g.count).append(sumCols.joinToString("") { ",${num(g.sums.getValue(it))}" }).append("\n") }
                if (groups.size > limit) append("……还有 ${groups.size - limit} 组没列出\n")
            } else {
                // 指定了排序就给前 limit 行（「最贵的 5 笔」）；没指定就给最近的 limit 行
                val rows = if (orderBy.isNotEmpty()) Tables.sortBy(t, hits, orderBy, desc).take(limit) else hits.takeLast(limit)
                if (hits.size > limit) append(if (orderBy.isNotEmpty()) "，下面是按「$orderBy」${if (desc) "从大到小" else "从小到大"}的前 $limit 行" else "，下面是最后 $limit 行")
                append("。\n行号,").append(t.columns.joinToString(",")).append("\n")
                rows.forEach { (i, r) -> append(i + 1).append(',').append(r.joinToString(",")).append("\n") }
            }
            sumCols.forEach { c ->
                val ci = t.columns.indexOf(c)
                val vals = hits.mapNotNull { Tables.number(it.value[ci]) }
                append("\n「$c」总合计 ${num(vals.sum())}（${vals.size} 个数字）")
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
            if (!approve("从「$name」删掉 ${nums.size} 行？", preview, "删除")) return@runTool "用户取消了，没有删。" to SearchRecord("删行：已取消")
        }
        withContext(Dispatchers.IO) { Tables.save(app, t.copy(rows = t.rows.filterIndexed { i, _ -> i + 1 !in nums })) }
        "已删 ${nums.size} 行，剩 ${t.rows.size - nums.size} 行。注意行号已经变了，再操作前先重新查。" to SearchRecord("「$name」删了 ${nums.size} 行")
    }
}

internal suspend fun ToolSession.deleteTable(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("删表：$name") {
        val t = withContext(Dispatchers.IO) { table(name) }
        if (!approve("删除整张表「$name」？", "共 ${t.rows.size} 行，删了找不回来。", "删除")) return@runTool "用户取消了，没有删。" to SearchRecord("删表：已取消")
        withContext(Dispatchers.IO) { Tables.delete(app, name) }
        "已删除「$name」。" to SearchRecord("删表：$name")
    }
}

/** edit_table：改行、删行、删表合成一个，按 action 分给原来的实现。 */
internal suspend fun ToolSession.editTable(args: JsonObject): String = when (args["action"].text()) {
    "update" -> updateRows(args)
    "delete_rows" -> deleteRows(args)
    "drop" -> deleteTable(args)
    else -> "action 要填 update、delete_rows 或 drop"
}
