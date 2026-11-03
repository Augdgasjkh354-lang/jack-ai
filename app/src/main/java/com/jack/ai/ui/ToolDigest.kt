package com.jack.ai.ui

import com.jack.ai.data.SearchRecord

/** 执行记录的总览：几百步的长任务一眼看出用了多久、主要在做什么、错了几次。 */
internal object ToolDigest {
    /** 这一步算哪一类（按显示的名字认，和执行记录里的图标一致）。 */
    fun kind(label: String): String = when {
        label.startsWith("浏览器") -> "浏览器"
        label.startsWith("操作手机") -> "手机"
        label.contains("邮件") || label.contains("邮箱") -> "邮件"
        label.startsWith("生成") || label.startsWith("修改") || label.startsWith("写入") ||
            label.startsWith("读取") || label.startsWith("查看") || label.startsWith("翻") -> "文件"
        label.startsWith("运行") || label.startsWith("计算") -> "代码"
        label.startsWith("下载") -> "下载"
        label.startsWith("使用技能") || label.startsWith("💬") -> "其他"
        else -> "查资料"
    }

    /** 「用时 18 分 · 浏览器 80 · 文件 20 · 手机 12 · 出错 3」；步数少（不到 10 步）时不用总览，返回空。 */
    fun line(searches: List<SearchRecord>, now: Long = System.currentTimeMillis()): String {
        if (searches.size < 10) return ""
        val parts = mutableListOf<String>()
        val start = searches.firstOrNull { it.at > 0 }?.at
        val end = if (searches.any { it.pending }) now else searches.lastOrNull { it.at > 0 }?.at
        if (start != null && end != null && end > start) parts += "用时 " + duration(end - start)
        searches.groupingBy { kind(it.query) }.eachCount().entries.sortedByDescending { it.value }.take(3)
            .forEach { (k, n) -> parts += "$k $n" }
        val errors = searches.count { it.error != null }
        if (errors > 0) parts += "出错 $errors"
        return parts.joinToString(" · ")
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "不到 1 分"
            min < 60 -> "$min 分"
            else -> "${min / 60} 小时" + if (min % 60 > 0) " ${min % 60} 分" else ""
        }
    }
}
