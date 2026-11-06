package com.jack.ai

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 闪退时把报错写进文件，下次打开时给用户看；另存一份去掉报错文字的，下次打开时自动发到 GitHub 的 Issues（标签 crash），
 * Claude 修 bug 时直接去那里看，不用用户复制粘贴。
 * 仓库是公开的：发出去的只有异常类型、代码位置、版本和机型，不带报错文字（里面可能夹着聊天内容、网址、Key）。
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")
    private fun reports(ctx: Context) = File(ctx.filesDir, "crash_reports")
    private const val MAX_PENDING = 20
    private const val PER_LAUNCH = 5

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

    /** 只留异常类型和调用栈，不留报错文字。 */
    internal fun stripped(e: Throwable): String =
        generateSequence(e) { it.cause }.take(5).joinToString("\nCaused by: ") { t ->
            t.javaClass.name + "\n" + t.stackTrace.take(40).joinToString("\n") { "    at $it" }
        }

    /** 同一个闪退只报一次：最里层的异常类型 + 它在我们代码里的头几个位置。 */
    internal fun signature(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val ours = root.stackTrace.filter { it.className.startsWith("com.jack.ai") }.take(3)
        val key = root.javaClass.name + ours.joinToString { "${it.className}.${it.methodName}" }
        return MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }

    /** Issue 标题：闪退：异常类型 @ 出事的那行代码。 */
    internal fun title(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val at = (root.stackTrace.firstOrNull { it.className.startsWith("com.jack.ai") } ?: root.stackTrace.firstOrNull())
            ?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return "闪退：${root.javaClass.simpleName}$at"
    }

    /** 把攒下的闪退报告发到 GitHub（要在后台线程调）。没填 GitHub 令牌就留着，等填了再发。 */
    fun upload(ctx: Context, token: String) {
        if (token.isBlank()) return
        val files = reports(ctx).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        val prefs = ctx.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
        val sent = prefs.getStringSet("sent", emptySet()).orEmpty().toMutableSet()
        for (f in files.take(PER_LAUNCH)) {
            val text = runCatching { f.readText() }.getOrNull() ?: run { f.delete(); null } ?: continue
            val sig = text.lineSequence().firstOrNull()?.removePrefix("sig: ").orEmpty()
            if (sig.isEmpty() || sig in sent) { f.delete(); continue }
            val title = text.lineSequence().drop(1).firstOrNull().orEmpty()
            val body = text.lineSequence().drop(2).joinToString("\n")
            val ok = runCatching {
                com.jack.ai.data.GitHub.send(token, "POST", "/repos/${com.jack.ai.data.Updater.REPO}/issues", kotlinx.serialization.json.buildJsonObject {
                    put("title", kotlinx.serialization.json.JsonPrimitive("$title [$sig]"))
                    put("body", kotlinx.serialization.json.JsonPrimitive("```\n$body\n```\n\n（App 自动上报，只有代码位置，不含报错文字）"))
                    put("labels", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("crash"))))
                })
            }.isSuccess
            // 发不出去（没网、令牌没 Issues 权限）就留着下次再试
            if (!ok) break
            sent += sig
            prefs.edit().putStringSet("sent", sent).apply()
            f.delete()
        }
    }

    /**
     * 安卓 11 起系统记着 App 上次是怎么死的。网页组件在系统底层崩（native）、界面卡死被判无响应（ANR），
     * 上面的 Java 异常处理都抓不到，用户也看不到报错框。下次打开时从这里补记：一样写进 [file] 弹框给用户看，
     * 一样排进待上报（只有类型、进程、调用位置，不带报错文字）。每条只记一次。
     */
    fun collectExits(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 16) }.getOrNull().orEmpty()
        if (exits.isEmpty()) return
        // 第一次用这段：只补最近三天的，更早的不翻旧账
        val seen = prefs.getLong("exitSeen", System.currentTimeMillis() - 3 * 24 * 3600_000L)
        prefs.edit().putLong("exitSeen", maxOf(seen, exits.maxOf { it.timestamp })).apply()
        exits.filter { it.timestamp > seen && it.reason in EXIT_KINDS }.sortedBy { it.timestamp }.takeLast(3).forEach { e ->
            runCatching { record(app, e) }
        }
    }

    private val EXIT_KINDS = setOf(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR)

    @android.annotation.TargetApi(30)
    private fun record(app: Context, e: android.app.ApplicationExitInfo) {
        val anr = e.reason == android.app.ApplicationExitInfo.REASON_ANR
        val kind = if (anr) "无响应（卡死）" else "底层崩溃"
        val raw = runCatching { e.traceInputStream?.use { it.readNBytesCompat(if (anr) 256 * 1024 else 2 * 1024 * 1024) } }.getOrNull()
        val frames = if (raw == null) emptyList() else if (anr) anrMainThread(String(raw)) else nativeFrames(raw)
        val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val whenText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(e.timestamp))
        val head = "版本 $ver，Android ${android.os.Build.VERSION.RELEASE}，${android.os.Build.MODEL}\n" +
            "$whenText $kind，进程 ${e.processName}\n"
        val detail = frames.joinToString("\n") { "    $it" }.ifBlank { "    （系统没给调用栈）" }
        // 给用户看的带系统的描述（可能有窗口名）；上报的不带
        if (!file(app).exists()) file(app).writeText(head + e.description.orEmpty().take(300) + "\n\n" + detail)
        val top = if (anr) frames.firstOrNull { "com.jack.ai" in it || "com.wangzan101" in it } ?: frames.firstOrNull().orEmpty()
            else frames.firstOrNull { it.startsWith("#00 ") } ?: frames.firstOrNull().orEmpty()
        val signatureFrames = if (anr) top else frames.filter { it.startsWith("#") }.take(3).joinToString("|").ifBlank { top }
        val sig = MessageDigest.getInstance("SHA-1").digest("$kind|${e.processName}|$signatureFrames".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        val dir = reports(app).apply { mkdirs() }
        File(dir, "${e.timestamp}.txt").writeText("sig: $sig\n闪退：$kind @ ${top.take(80).ifBlank { e.processName }}\n" + head + "\n" + detail)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, minOf(n, max - out.size())) }
        return out.toByteArray()
    }

    /** ANR 记录里主线程那一段：卡在哪一行，看这里。 */
    internal fun anrMainThread(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyList()
        return lines.drop(start).takeWhile { it.isNotBlank() }.map { it.trim() }
            .filter { it.startsWith("at ") || it.startsWith("native:") || it.startsWith("- ") || it.startsWith("\"main\"") }.take(40)
    }

    /** Android 12+ 的原生记录按 tombstone.proto 提取崩溃线程及回溯，不混入其他线程。 */
    internal fun nativeFrames(bytes: ByteArray): List<String> = NativeTombstone.frames(bytes)

    private class Handler(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val head = "版本 $ver，Android ${android.os.Build.VERSION.RELEASE}，${android.os.Build.MODEL}\n线程 ${t.name}\n\n"
                file(app).writeText(head + e.stackTraceToString().take(12_000))
                val dir = reports(app).apply { mkdirs() }
                dir.listFiles()?.sortedBy { it.name }?.dropLast(MAX_PENDING - 1)?.forEach { it.delete() }
                File(dir, "${System.currentTimeMillis()}.txt")
                    .writeText("sig: ${signature(e)}\n${title(e)}\n" + head + stripped(e).take(12_000))
            }
            previous?.uncaughtException(t, e)
        }
    }
}
