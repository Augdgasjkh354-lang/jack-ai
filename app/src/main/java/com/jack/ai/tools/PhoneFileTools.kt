package com.jack.ai.tools

import com.jack.ai.data.PhoneFiles
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.ToolSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 手机文件夹（phone_files）：只在用户授权的文件夹里。list 列一层、search 按名字搜、import 拷进对话文件夹、
 * export 把对话文件夹里的文件拷过去、rename 改名、move 搬到别的文件夹、mkdir 建文件夹、delete 删（先问）。
 */
internal suspend fun ToolSession.phoneFiles(args: JsonObject): String {
    val action = args["action"].text().trim()
    val folders = settings.phoneFolders
    if (folders.isEmpty()) return PhoneFiles.NO_FOLDER
    val path = args["path"].text().trim()
    val paths = (args["paths"] as? JsonArray)?.map { it.text().trim() }?.filter { it.isNotEmpty() } ?: listOfNotNull(path.takeIf { it.isNotEmpty() })
    val name = args["name"].text().trim()
    val to = args["to"].text().trim()
    when (action) {
        "list" -> {}
        "search" -> if (args["query"].text().isBlank()) return "search 要填 query（文件名里的字）"
        "import", "delete" -> if (paths.isEmpty()) return "$action 要填 path（或 paths，几个文件）"
        "export" -> if (name.isBlank() && (args["names"] as? JsonArray).isNullOrEmpty()) return "export 要填 name（对话文件夹里的文件名，几个就用 names）和 to（手机文件夹的路径）"
        "rename" -> if (path.isEmpty() || name.isBlank()) return "rename 要填 path 和 name（新名字）"
        "move" -> if (paths.isEmpty() || to.isBlank()) return "move 要填 path（或 paths）和 to（搬到哪个文件夹）"
        "mkdir" -> if (path.isEmpty() || name.isBlank()) return "mkdir 要填 path（在哪个文件夹里建）和 name"
        else -> return "action 要填 list、search、import、export、rename、move、mkdir 或 delete"
    }
    val label = "手机文件夹 " + when (action) {
        "list" -> "看 " + path.ifBlank { "全部" }
        "search" -> "搜「${args["query"].text().take(20)}」"
        "import" -> "拷进对话：" + paths.first().substringAfterLast('/') + if (paths.size > 1) " 等 ${paths.size} 个" else ""
        "export" -> "存到 $to"
        "rename" -> "改名 ${path.substringAfterLast('/')} → $name"
        "move" -> "搬到 $to"
        "mkdir" -> "建文件夹 $name"
        else -> "删除 " + paths.first().substringAfterLast('/') + if (paths.size > 1) " 等 ${paths.size} 个" else ""
    }
    return runTool(label) {
        val ctx = app
        val text = withContext(Dispatchers.IO) {
            when (action) {
                "list" -> PhoneFiles.list(ctx, folders, path)
                "search" -> PhoneFiles.search(ctx, folders, path, args["query"].text())
                "import" -> {
                    val got = paths.take(30).map { p -> PhoneFiles.import(ctx, PhoneFiles.find(ctx, folders, p).second, wsDir) }
                    got.forEach { ui.attach(it) }
                    "已拷进对话文件夹：" + got.joinToString("、") { "「${it.name}」（${ToolSizes.text(it.length())}）" } +
                        "。手机里的原文件没动；接下来用 read_file、convert_file、run_python 处理拷过来的这份。"
                }
                "export" -> {
                    val (_, dir) = PhoneFiles.find(ctx, folders, to)
                    val names = (args["names"] as? JsonArray)?.map { it.text() }?.filter { it.isNotBlank() } ?: listOf(name)
                    val done = names.take(50).map { PhoneFiles.export(ctx, wsFile(it), dir) }
                    "已存到手机的「${to.trim('/')}」：" + done.joinToString("、") { "「${it.name}」" } + "。用户在文件管理器里能找到。"
                }
                "rename" -> {
                    val (_, d) = PhoneFiles.find(ctx, folders, path)
                    val old = d.name
                    if (!d.renameTo(com.jack.ai.data.Workspace.safeName(name))) throw IllegalStateException("改名没成功（可能重名了）")
                    "已把「$old」改名为「${d.name}」。"
                }
                "mkdir" -> {
                    val (_, d) = PhoneFiles.find(ctx, folders, path)
                    val made = d.findFile(name)?.takeIf { it.isDirectory } ?: d.createDirectory(com.jack.ai.data.Workspace.safeName(name)) ?: throw IllegalStateException("没建成")
                    "文件夹「${path.trim('/')}/${made.name}」好了。"
                }
                else -> null
            }
        }
        if (text != null) return@runTool text to SearchRecord(label)
        // 搬、删：会改动用户手机里的东西，先列清楚再问
        val found = withContext(Dispatchers.IO) { paths.take(50).map { p -> p to PhoneFiles.find(ctx, folders, p).second } }
        val list = found.joinToString("\n") { (p, d) -> "· $p" + if (d.isDirectory) "/（文件夹，连里面的一起）" else "（${ToolSizes.text(d.length())}）" }
        if (action == "move") {
            if (!approve("搬 ${found.size} 个文件到「$to」？", list, "搬")) return@runTool "用户没同意，没有搬。" to SearchRecord("$label：已取消")
            val (_, dir) = withContext(Dispatchers.IO) { PhoneFiles.find(ctx, folders, to) }
            val done = withContext(Dispatchers.IO) { found.map { (_, d) -> PhoneFiles.move(ctx, d, dir).name } }
            return@runTool "已搬到「${to.trim('/')}」：" + done.joinToString("、") to SearchRecord(label)
        }
        if (!approve("删除手机里的 ${found.size} 项？", "删了找不回来。\n\n$list", "删除")) return@runTool "用户没同意，没有删。" to SearchRecord("$label：已取消")
        val failed = withContext(Dispatchers.IO) { found.filterNot { (_, d) -> d.delete() }.map { it.first } }
        (if (failed.isEmpty()) "已删除 ${found.size} 项。" else "删了 ${found.size - failed.size} 项，这些没删掉：" + failed.joinToString("、")) to SearchRecord(label)
    }
}
