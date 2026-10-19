package com.jack.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** 单个文件最多保留的文字数，避免一次塞爆上下文。 */
private const val MAX_FILE_CHARS = 200_000
private const val MAX_ORIGINAL_BYTES = 30L * 1024 * 1024
/** 当文字读的文件最多这么大；再大的只放进对话文件夹，不读内容。 */
private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

object Attachments {

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
    }

    /** 把图片压缩到最长边 1600 像素，存进 App 自己的目录。 */
    suspend fun importImage(ctx: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(ctx, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("无法读取这张图片")
        bmp = rotateByExif(ctx, uri, bmp)
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > 1600) {
            val scale = 1600f / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val att = Attachment(kind = "image", name = name)
        val file = File(dir, "${att.id}.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        att.copy(path = file.absolutePath)
    }

    private fun rotateByExif(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap = runCatching {
        val orientation = ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }.getOrDefault(bmp)

    /**
     * 读取文件里的文字：支持 PDF、Word(docx)、以及各种纯文本/代码文件。
     * keepOriginal=true 时把原文件也存一份（发送后放进对话文件夹），读不出文字的文件也能收。
     */
    suspend fun importFile(ctx: Context, uri: Uri, keepOriginal: Boolean = false): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(ctx, uri)
        val lower = name.lowercase()
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val read = runCatching {
            when {
                lower.endsWith(".pdf") || mime == "application/pdf" -> readPdf(ctx, uri)
                lower.endsWith(".docx") -> readDocx(ctx, uri)
                lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt") ->
                    throw IllegalArgumentException("暂不支持老版 Office 格式，请另存为 docx 或 PDF")
                else -> readText(ctx, uri)
            }.trim()
        }
        val text = read.getOrNull().orEmpty()
        if (!keepOriginal) {
            read.exceptionOrNull()?.let { throw it }
            if (text.isEmpty()) throw IllegalArgumentException("没能从「$name」里读出文字（可能是扫描件，可以截图后当图片发）")
        }
        val att = Attachment(kind = "file", name = name)
        val path = if (keepOriginal) {
            val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > MAX_ORIGINAL_BYTES) {
                if (text.isEmpty()) throw IllegalArgumentException("「$name」太大了（超过 30 MB）")
                ""
            } else {
                val dir = File(ctx.filesDir, "staging/${att.id}").apply { mkdirs() }
                val f = File(dir, Workspace.safeName(name))
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                f.absolutePath
            }
        } else ""
        val clipped = if (text.length > MAX_FILE_CHARS) text.take(MAX_FILE_CHARS) + "\n…（文件太长，后面省略）" else text
        att.copy(text = clipped, path = path)
    }

    private fun readPdf(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
        } ?: ""

    private fun readDocx(ctx: Context, uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        return xml
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<w:tab/>"), "\t")
                            .replace(Regex("<w:br/>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                    }
                }
            }
        }
        return ""
    }

    private fun readText(ctx: Context, uri: Uri): String {
        // 先读一小段判断是不是文字，再限量读：几百 MB 的视频当文件发过来，整个读进内存会闪退
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= MAX_TEXT_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (out.size() > MAX_TEXT_BYTES) throw IllegalArgumentException("文件太大，没法当文字读")
            out.toByteArray()
        } ?: return ""
        val sample = bytes.take(4000)
        if (sample.count { it == 0.toByte() } > 0) {
            throw IllegalArgumentException("这个文件不是文字格式，暂时读不了")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun imageDataUrl(path: String): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** 生成缩略图用于界面显示。 */
    /** 把下载的图片统一转成 JPEG（最长边不超过 maxSide），返回 (字节, 宽, 高)。 */
    fun toJpeg(bytes: ByteArray, maxSide: Int): Triple<ByteArray, Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val r = Triple(out.toByteArray(), bmp.width, bmp.height)
        bmp.recycle()
        r
    }.getOrNull()

    fun thumbnail(path: String, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
