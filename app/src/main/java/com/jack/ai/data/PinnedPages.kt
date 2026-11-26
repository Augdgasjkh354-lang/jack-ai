package com.jack.ai.data

import android.content.Context
import java.io.File

/**
 * 侧边栏「我的页面」：用户（或 Kavi 帮着）放进去的小工具网页，像自带的页面一样点开就用。
 * 设置里存的是相对对话文件夹根目录的路径（「对话id/名字.html」），文件删了这一项就不显示。
 */
object PinnedPages {
    /** 最多放几个，多了侧边栏太长。 */
    const val MAX = 12

    fun rel(ctx: Context, f: File): String = f.canonicalFile.relativeTo(Workspace.root(ctx).canonicalFile).path

    fun file(ctx: Context, rel: String): File? =
        File(Workspace.root(ctx), rel).takeIf { it.isFile && !rel.contains("..") && it.extension.equals("html", true) }

    fun title(rel: String) = rel.substringAfterLast('/').removeSuffix(".html")

    /** 放进去（已经在就不动）；满了返回 null。 */
    fun add(list: List<String>, rel: String): List<String>? = when {
        rel in list -> list
        list.size >= MAX -> null
        else -> list + rel
    }
}
