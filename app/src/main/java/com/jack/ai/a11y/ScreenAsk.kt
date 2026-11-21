package com.jack.ai.a11y

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import com.jack.ai.MainActivity
import java.io.File

/**
 * 「问这一屏」：用户点悬浮球或长按电源键（Kavi 设成默认助理时）叫 Kavi，把当前屏幕的文字和截图带进一个新对话，
 * 用户接着问「这是什么」「帮我回」。这是用户自己叫的，所以不限于勾过的 App；涉及钱的 App 照样不读。
 */
object ScreenAsk {
    const val ACTION = "com.jack.ai.ASK_SCREEN"

    /** 这个 App 能不能读（不能就是原因）。 */
    fun refuse(ctx: Context, pkg: String, label: String): String? = when {
        pkg == ctx.packageName -> "这就是 Kavi"
        PhoneGuard.money(pkg, label) -> "「$label」涉及钱，Kavi 不读"
        else -> null
    }

    /** 屏幕内容存成文件，打开 Kavi 开一个新对话带上它们。 */
    fun launch(ctx: Context, app: String, text: String, shot: Bitmap?) = ctx.startActivity(intent(ctx, app, text, shot))

    fun intent(ctx: Context, app: String, text: String, shot: Bitmap?): Intent {
        val dir = File(ctx.cacheDir, "screen_ask").apply { mkdirs() }
        // 只留最近几次的
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = app.replace(Regex("[\\\\/:*?\"<>|]"), "").take(20)
        val txt = File(dir, "${name}屏幕上的字-$stamp.txt").apply { writeText(text) }
        val img = shot?.let { b ->
            File(dir, "${name}截图-$stamp.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        }
        return Intent(ctx, MainActivity::class.java).setAction(ACTION)
            .putExtra("app", app).putExtra("text", txt.path.takeIf { text.isNotBlank() }).putExtra("shot", img?.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // ---------- 圈出一块来问 ----------

    /** 屏幕上的一行字和它的位置（屏幕坐标：左、上、右、下）。 */
    @kotlinx.serialization.Serializable
    data class Line(val t: String, val b: List<Int>)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 先打开圈选页（[ScreenPickActivity]）：用手指圈出一块，只问这一块；或者点「问整屏」。
     * 没截到图（安卓 11 以下、截图失败）就直接问整屏。
     */
    fun pickIntent(ctx: Context, app: String, screen: Screen, shot: Bitmap?): Intent {
        if (shot == null) return intent(ctx, app, ScreenText.plain(screen), null)
        val dir = File(ctx.cacheDir, "screen_ask").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        val img = File(dir, "pick-${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val lines = screen.elements.mapNotNull { e -> ScreenText.plainLine(e)?.let { Line(it, e.bounds.toList()) } }
        val txt = File(dir, img.nameWithoutExtension + ".json").apply {
            writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Line.serializer()), lines))
        }
        return Intent(ctx, ScreenPickActivity::class.java).putExtra("app", app).putExtra("shot", img.path).putExtra("lines", txt.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun readLines(f: File): List<Line> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Line.serializer()), f.readText())
    }.getOrDefault(emptyList())

    /** 圈出的那块（屏幕坐标）里的字：一行字一半以上落在里面，或者中心在里面，就算。 */
    fun inside(lines: List<Line>, l: Int, t: Int, r: Int, b: Int): List<String> = lines.filter { line ->
        if (line.b.size < 4) return@filter false
        val (x1, y1, x2, y2) = line.b
        val w = (x2 - x1).coerceAtLeast(1); val h = (y2 - y1).coerceAtLeast(1)
        val iw = (minOf(x2, r) - maxOf(x1, l)).coerceAtLeast(0); val ih = (minOf(y2, b) - maxOf(y1, t)).coerceAtLeast(0)
        val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
        iw.toLong() * ih >= w.toLong() * h / 2 || (cx in l..r && cy in t..b)
    }.map { it.t }

    /** 所有的字拼回整屏的样子（「问整屏」用）。 */
    fun allText(app: String, lines: List<Line>): String = "「$app」屏幕上的内容：\n" + lines.joinToString("") { it.t + "\n" }
}
