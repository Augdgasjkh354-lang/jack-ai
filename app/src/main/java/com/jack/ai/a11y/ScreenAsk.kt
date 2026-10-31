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
}
