package com.jack.ai

import java.io.File

/**
 * 别的 App 递进来的文件地址能不能收（见 MainActivity.sharedFromOutside）。
 * Kavi 读得到自己的私有文件（聊天记录、设置、Key）：file:// 指到 Kavi 的数据目录、Kavi 自己的 content://，一律不收，
 * 免得被骗着把私有文件当成附件放进对话、一发就交给模型。正常分享都是对方 App 自己的 content://。
 */
object ShareGuard {
    fun outside(scheme: String?, authority: String?, path: String?, pkg: String, privateRoots: List<File>): Boolean = when (scheme?.lowercase()) {
        "content" -> authority.orEmpty().split(';').none { it == pkg || it.startsWith("$pkg.") }
        "file" -> runCatching {
            val p = File(path.orEmpty()).canonicalPath
            privateRoots.none { d -> val root = d.canonicalPath; p == root || p.startsWith(root + File.separator) }
        }.getOrDefault(false)
        else -> false
    }
}
