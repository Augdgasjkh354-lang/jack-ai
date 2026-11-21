// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported from Mika 8bc745e (RootShell / SuProbe / launchHelperAsRoot); Jack AI modification: one small file,
// a single opt-in switch, no root-command tool.
package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.shell.HelperHandoff
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 已 root 的手机：经 su 以 shell 身份（uid 2000）起同一个手机助手，不用配无线调试、不用电脑，重启后看门狗自己再起。
 * 只按能力判断（找得到 su、跑出来的 uid 是 2000），不认是哪种 root。都会阻塞，只在 IO 线程上调。
 */
object RootStart {
    private val DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")

    /** su 的完整路径；找不到为 null（只看文件在不在，不会弹授权框）。 */
    fun su(): String? = (System.getenv("PATH").orEmpty().split(':').map { it.trim().trimEnd('/') }.filter { it.startsWith("/") } + DIRS)
        .distinct().map { "$it/su" }.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

    /** 问 su 一次能不能切到 shell 身份（第一次会弹授权框，给用户 [timeoutMs]）。 */
    fun ask(timeoutMs: Long = 30_000): Boolean {
        val su = su() ?: return false
        val p = runCatching { ProcessBuilder(su, HelperHandoff.SHELL_UID.toString(), "-c", "id -u").redirectErrorStream(true).start() }.getOrNull() ?: return false
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) { p.destroyForcibly(); return false }
        val out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        return out.lineSequence().any { it.trim() == HelperHandoff.SHELL_UID.toString() }
    }

    /**
     * 起助手：`su 2000 -c 'id -u; <和无线调试同样的命令>'`。先读到 2000 才写一次性口令；交接核对和无线调试起的一样。
     * [handoff] 等交接到了（或超时）才返回，之后关掉标准输入，sh 退出，助手在 setsid 另起的会话里不受影响。
     */
    suspend fun launch(command: String, nonceLine: ByteArray, handoff: suspend () -> Unit): Boolean {
        val su = su() ?: return false
        val p = ProcessBuilder(su, HelperHandoff.SHELL_UID.toString(), "-c", "id -u; $command").redirectErrorStream(true).start()
        try {
            val first = runCatching { p.inputStream.bufferedReader().readLine() }.getOrNull()?.trim()
            if (first != HelperHandoff.SHELL_UID.toString()) return false
            p.outputStream.apply { write(nonceLine); flush() }
            handoff()
            return true
        } finally {
            runCatching { p.outputStream.close() }
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        }
    }
}
