package com.jack.ai.data.games

import android.content.Context
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import java.io.File

/**
 * 所有对话里的游戏（写了 <meta name="kavi-kind" content="game"> 的小工具网页）。
 * 文件工具平常只看当前对话的文件夹；游戏例外：任何对话里用「游戏/名字.html」都能读、能改（不能删），
 * 用户不用跑回做它的那个对话。隐私项目里的游戏不算（那里的东西不出项目）。
 */
object GameFiles {
    const val PREFIX = "游戏/"

    private val META = Regex("""<meta[^>]+name=["']kavi-kind["'][^>]+content=["']game["']|<meta[^>]+content=["']game["'][^>]+name=["']kavi-kind["']""", RegexOption.IGNORE_CASE)

    fun isGame(head: String): Boolean = META.containsMatchIn(head)

    private fun head(f: File): String = runCatching {
        f.bufferedReader().use { r -> CharArray(4096).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } }
    }.getOrDefault("")

    /** 全部游戏，最近改过的在前。 */
    fun all(ctx: Context): List<File> {
        val hidden = Store(ctx).loadProjects().filter { it.isolated }.map { "p_" + it.id }.toSet()
        return Workspace.root(ctx).listFiles().orEmpty().filter { it.isDirectory && it.name !in hidden }
            .flatMap { dir -> Workspace.files(dir).filter { it.extension.equals("html", true) } }
            .filter { isGame(head(it)) }
            .sortedByDescending { it.lastModified() }
    }

    /** 按名字找（带不带「游戏/」、带不带 .html 都行）；同名的取最近改过的。 */
    fun find(ctx: Context, name: String): File? {
        val n = name.trim().removePrefix(PREFIX).substringAfterLast('/').trim()
        if (n.isEmpty()) return null
        val list = all(ctx)
        return list.firstOrNull { it.name == n } ?: list.firstOrNull { it.name.equals(n, true) }
            ?: list.firstOrNull { it.nameWithoutExtension.equals(n.removeSuffix(".html"), true) }
    }

    /** 给 Kavi 看的一行：有哪些游戏、怎么写名字。没有游戏返回空。 */
    fun note(ctx: Context, except: File? = null): String {
        val names = all(ctx).filter { except == null || it.parentFile?.canonicalPath != except.canonicalPath }.map { PREFIX + it.name }.distinct()
        return if (names.isEmpty()) "" else "其他对话里的游戏（名字照写就能用 read_file、edit_file 读和改，不用回原来的对话）：" + names.joinToString("、")
    }
}
