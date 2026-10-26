package com.jack.ai.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jack.ai.api.BrowserDownload
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 下载的东西存到哪：对话文件夹（Kavi 之后能读、能处理，聊天里显示成卡片），
 * 或者手机的「下载/JackAI」文件夹（文件管理器、相册、别的 App 都找得到）。
 */
object Downloads {
    /** 存好的一个文件。file 只有存进对话文件夹（或老系统的 App 下载目录）时才有。 */
    data class Saved(val name: String, val uri: Uri, val mime: String, val where: String, val file: File? = null)

    /** 一个去处：sink 交给 BrowserDownload 写，写完调 finish 拿结果。 */
    class Target(val sink: BrowserDownload.Sink, val finish: () -> Saved)

    const val PHONE_FOLDER = "JackAI"
    const val PHONE_WHERE = "手机的「下载/$PHONE_FOLDER」文件夹"

    /** 存进某个文件夹（同名加编号）。 */
    fun toDir(ctx: Context, dir: File, name: String, mime: String): Target {
        val f = Workspace.uniqueFile(dir, name)
        val sink = object : BrowserDownload.Sink {
            override fun open(append: Boolean): OutputStream = FileOutputStream(f, append)
            override fun discard() { f.delete() }
        }
        return Target(sink) {
            Saved(f.name, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), mime, "对话文件夹", f)
        }
    }

    /**
     * 存到手机的「下载/JackAI」（sub 不为空就再建一层，比如一页的图片放一起）。Android 10 以上走 MediaStore，不用存储权限；
     * 更老的系统存到 App 自己的下载目录。
     */
    fun toPhone(ctx: Context, name: String, mime: String, sub: String = ""): Target {
        val app = ctx.applicationContext
        val folder = PHONE_FOLDER + sub.takeIf { it.isNotBlank() }?.let { "/" + Workspace.safeName(it) }.orEmpty()
        if (Build.VERSION.SDK_INT < 29) {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), sub.takeIf { it.isNotBlank() }?.let { Workspace.safeName(it) }.orEmpty())
            val t = toDir(app, dir, name, mime)
            return Target(t.sink) { t.finish().copy(where = "Jack 的下载目录（Android/data/${app.packageName}/files/Download）") }
        }
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, Workspace.safeName(name))
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("没能在「下载」文件夹里建文件")
        val sink = object : BrowserDownload.Sink {
            override fun open(append: Boolean): OutputStream =
                resolver.openOutputStream(uri, if (append) "wa" else "wt") ?: throw IOException("打不开要写的文件")
            override fun discard() { runCatching { resolver.delete(uri, null, null) } }
        }
        return Target(sink) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // 重名时系统会自动改名，以系统里的为准
            val shown = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            Saved(shown, uri, mime, "手机的「下载/$folder」文件夹")
        }
    }

    /** 已经在手边的文件（拼好的视频之类）拷到手机「下载/JackAI」。 */
    fun copyToPhone(ctx: Context, f: File, mime: String, sub: String = ""): Saved {
        val t = toPhone(ctx, f.name, mime, sub)
        try {
            t.sink.open(false).use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
        } catch (e: Exception) {
            t.sink.discard(); throw e
        }
        return t.finish()
    }
}
