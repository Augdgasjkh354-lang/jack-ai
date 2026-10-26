package com.jack.ai.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 在 App 里跑 Python。同一时间只跑一段，因为工作目录和输出是全局的。 */
object PyRunner {
    private val lock = Mutex()

    data class Result(val output: String, val changed: List<File>)

    suspend fun run(ctx: Context, code: String, dir: File, timeoutSec: Int = 90): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            dir.mkdirs()
            val before = snapshot(dir)
            val out = Python.getInstance().getModule("jack_runner")
                .callAttr("run", code, dir.absolutePath, timeoutSec).toString()
            val changed = Workspace.files(dir).filter { f -> before[f.name] != f.lastModified() to f.length() }
            Result(out, changed)
        }
    }

    /** 调一个现成的 Python 函数（比如 jack_files 里的），参数都是字符串或数字，返回它的字符串结果。 */
    suspend fun call(ctx: Context, module: String, fn: String, vararg args: Any): String = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            Python.getInstance().getModule(module).callAttr(fn, *args).toString()
        }
    }

    /** 只编译不运行，返回语法错误（第几行、什么错）；没错返回 null。 */
    suspend fun syntaxError(ctx: Context, code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            Python.getInstance().getModule("jack_runner").callAttr("check", code).toString().ifBlank { null }
        }.getOrNull()
    }

    private fun snapshot(dir: File) = Workspace.files(dir).associate { it.name to (it.lastModified() to it.length()) }
}
