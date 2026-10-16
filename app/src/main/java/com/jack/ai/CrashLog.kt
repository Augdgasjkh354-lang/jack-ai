package com.jack.ai

import android.content.Context
import java.io.File

/** 闪退时把报错写进文件，下次打开时给用户看，方便发给开发者。 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /** 读出上次的报错并删掉文件；没有就返回 null。 */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }?.takeIf { it.isNotBlank() }
    }

    private class Handler(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val text = "版本 $ver，Android ${android.os.Build.VERSION.RELEASE}，${android.os.Build.MODEL}\n" +
                    "线程 ${t.name}\n\n" + e.stackTraceToString().take(12_000)
                file(app).writeText(text)
            }
            previous?.uncaughtException(t, e)
        }
    }
}
