// Jack AI addition next to the Mika port (Apache-2.0 tree); Shizuku API by RikkaApps (Apache-2.0).
package com.wangzan101.mika.cap.phone.setup

import android.content.pm.PackageManager
import com.wangzan101.mika.core.shell.HelperHandoff
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 装了 Shizuku 的手机：经 Shizuku 以 shell 身份（uid 2000）起同一个手机助手，不用无线调试配对、不用 root。
 * Shizuku 本身要用户在 Shizuku App 里启动一次（无线调试或电脑），之后 Kavi 每次起助手都经它。
 * Shizuku 以 root 身份运行时切不到 shell 身份，这种手机直接用「用 root 启动」。思路来自 Operit、Zafiro 的 Shizuku 通道。
 */
object ShizukuStart {
    /** Shizuku 在跑、Kavi 能连上它。 */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun granted(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shizuku 是以 shell 身份跑的（root 身份的这里不用）。 */
    fun asShell(): Boolean = runCatching { Shizuku.getUid() == HelperHandoff.SHELL_UID }.getOrDefault(false)

    /** 问用户要 Shizuku 授权（Shizuku 弹框）；已经有了直接 true。 */
    suspend fun request(): Boolean {
        if (!running()) return false
        if (granted()) return true
        val code = 4711
        return suspendCancellableCoroutine { c ->
            val l = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (c.isActive) c.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(l)
            c.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(l) }
            runCatching { Shizuku.requestPermission(code) }.onFailure { Shizuku.removeRequestPermissionResultListener(l); if (c.isActive) c.resume(false) }
        }
    }

    /** Shizuku 13 把 newProcess 收成了私有的，按它的老接口反射调用（常见做法）。 */
    private fun process(args: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, args, null, null) as Process
    }

    /** Shizuku 刚连上时调（App 启动后它的 Binder 是过一会儿才到的）。 */
    fun onReady(block: () -> Unit) = runCatching { Shizuku.addBinderReceivedListenerSticky { block() } }

    /** 起助手：和 root 启动一样，先读到 uid 2000 才写一次性口令，交接到了（或超时）再关标准输入。 */
    suspend fun launch(command: String, nonceLine: ByteArray, handoff: suspend () -> Unit): Boolean {
        if (!granted() || !asShell()) return false
        val p = runCatching { process(arrayOf("sh", "-c", "id -u; $command")) }.getOrNull() ?: return false
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
