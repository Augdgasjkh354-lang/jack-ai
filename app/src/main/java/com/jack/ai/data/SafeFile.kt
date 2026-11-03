package com.jack.ai.data

import android.util.AtomicFile
import java.io.File

object SafeFile {
    fun readText(file: File): String =
        String(AtomicFile(file).readFully(), Charsets.UTF_8)

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
