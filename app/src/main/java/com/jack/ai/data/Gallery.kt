package com.jack.ai.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 把对话文件夹里的视频、图片存进手机相册（视频在 Movies/Kavi，图片在 Pictures/Kavi），别的 App（剪映、抖音、小红书）选素材时能直接看到。 */
object Gallery {
    const val FOLDER = "Kavi"
    private val VIDEO = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    enum class Kind { VIDEO, IMAGE }

    /** 能存进相册的是哪一类；别的文件返回 null。 */
    fun kindOf(f: File): Kind? = when (f.extension.lowercase()) {
        in VIDEO -> Kind.VIDEO
        in IMAGE -> Kind.IMAGE
        else -> null
    }

    /** 存一个文件，成功返回相册里的名字；不支持的类型或写不进去返回 null。 */
    fun save(ctx: Context, f: File): String? {
        val kind = kindOf(f) ?: return null
        val ext = f.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (kind == Kind.VIDEO) "video/mp4" else "image/png"
        val video = kind == Kind.VIDEO
        val name = f.name
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, Workspace.uniqueFile(dir, name).absolutePath)
            }
        }
        val collection: Uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("打不开相册文件")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
