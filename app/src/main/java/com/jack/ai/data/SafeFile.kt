package com.jack.ai.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 写到一半被杀也不坏的文本文件：先写同目录的 .tmp、刷到磁盘，再一步换成正式文件。
 * 被杀时要么还是旧的、要么已经是新的，不会只剩半截。读的时候就是普通文件，老文件照样能读。
 * 不用 android.util.AtomicFile：纯 JVM 实现，纯逻辑单测里也能跑。
 */
object SafeFile {
    fun readText(file: File): String = file.readText(Charsets.UTF_8)

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
