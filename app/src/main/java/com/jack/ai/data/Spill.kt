package com.jack.ai.data

import java.io.File
import java.security.MessageDigest

/**
 * 长任务里压掉的大段工具结果，全文先存进对话文件夹的「.spill」里（文件夹列表里看不到），
 * 上下文只留开头、链接和结尾，要细看时 read_file 读回来，不用把搜索、读网页再做一遍。
 * 文件名按内容算，同一段结果总是同一个名字：压过的消息每轮都一样，缓存照样命中。
 */
object Spill {
    const val DIR = ".spill"
    private val NAME = Regex("""r[0-9a-f]{12}\.txt""")

    /** 存好返回给模型用的名字（「.spill/r….txt」）；存不了返回 null。 */
    fun save(wsDir: File, text: String): String? = runCatching {
        val name = "r" + MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12) + ".txt"
        val f = File(File(wsDir, DIR).apply { mkdirs() }, name)
        if (!f.exists()) f.writeText(text)
        "$DIR/$name"
    }.getOrNull()

    /** 「.spill/r….txt」对应的文件；不是这个格式或不存在返回 null。 */
    fun file(wsDir: File, name: String): File? {
        val n = name.trim().removePrefix("./")
        if (!n.startsWith("$DIR/")) return null
        val base = n.removePrefix("$DIR/")
        if (!NAME.matches(base)) return null
        return File(File(wsDir, DIR), base).takeIf { it.isFile }
    }
}
