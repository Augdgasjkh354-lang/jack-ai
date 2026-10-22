package com.jack.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.TextLayoutSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把一个对话导出成 Markdown、PDF 或一张长图，用来分享。 */
object ChatExport {
    enum class Format(val ext: String, val mime: String) {
        MARKDOWN("md", "text/markdown"),
        PDF("pdf", "application/pdf"),
        IMAGE("png", "image/png"),
    }

    private val TIME get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun markdown(conv: Conversation): String = buildString {
        append("# ").append(conv.title).append("\n\n")
        append("导出时间：").append(TIME.format(Date())).append("\n\n---\n\n")
        for (m in conv.messages) {
            if (m.role == "user") {
                append("**我** · ").append(TIME.format(Date(m.createdAt))).append("\n\n")
                if (m.content.isNotBlank()) append(m.content.trim()).append("\n\n")
                val files = m.attachments.filter { it.kind == "file" }.map { it.name }
                val imgs = m.attachments.count { it.kind == "image" }
                val extra = listOfNotNull(
                    files.takeIf { it.isNotEmpty() }?.joinToString("、"),
                    "$imgs 张图片".takeIf { imgs > 0 },
                )
                if (extra.isNotEmpty()) append("（附件：").append(extra.joinToString("，")).append("）\n\n")
            } else {
                append("**Jack AI**")
                if (m.model.isNotEmpty()) append(" · ").append(modelLabel(m.model))
                append("\n\n")
                when {
                    m.content.isNotBlank() -> append(m.content.trim()).append("\n\n")
                    m.error != null -> append("（出错：").append(m.error).append("）\n\n")
                }
                if (m.files.isNotEmpty()) append("（生成的文件：").append(m.files.joinToString("、") { it.name }).append("）\n\n")
            }
        }
    }.trimEnd() + "\n"

    /** 生成导出文件，放在缓存目录里（每次导出前清掉旧的）。 */
    fun export(ctx: Context, conv: Conversation, format: Format): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val base = conv.title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(40).ifBlank { "对话" }
        val file = File(dir, "$base.${format.ext}")
        FileOutputStream(file).use { out ->
            when (format) {
                Format.MARKDOWN -> out.write(markdown(conv).toByteArray())
                Format.PDF -> PdfWriter.write(markdown(conv), out)
                Format.IMAGE -> renderImage(ctx, conv).let { bmp ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                }
            }
        }
        return file
    }

    // ---------- 长图 ----------

    private const val W = 1080
    private const val PAD = 56
    private const val MAX_H = 24_000 // 再长手机内存吃不消，只保留最近的部分
    private const val BG = 0xFFFAF8F5.toInt()
    private const val INK = 0xFF2B2722.toInt()
    private const val MUTED = 0xFF8A8178.toInt()
    private const val ACCENT = 0xFFC4673F.toInt()
    private const val BUBBLE = 0xFFEFE9E1.toInt()
    private const val CODE_BG = 0xFFF0EBE4.toInt()

    /** 一块内容：高度 + 画法（画在 y=0 开始的位置）。 */
    private class Block(val height: Int, val draw: (Canvas) -> Unit)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()

    private fun markwon(ctx: Context): Markwon = Markwon.builder(ctx)
        .usePlugin(TablePlugin.create(ctx))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.codeBackgroundColor(CODE_BG).codeBlockBackgroundColor(CODE_BG)
                    .codeTextColor(INK).codeBlockTextColor(INK)
            }
        })
        .build()

    /**
     * Markdown 排版。表格的行高要画过一次才知道，所以先在小画布上画一遍，再重新排版。
     */
    private fun markdownLayout(mw: Markwon, md: String, p: TextPaint, width: Int): StaticLayout {
        val spanned = SpannableString(mw.toMarkdown(md))
        var l = layout(spanned, p, width)
        TextLayoutSpan.applyTo(spanned, l)
        val probe = Bitmap.createBitmap(width, 1, Bitmap.Config.ALPHA_8)
        l.draw(Canvas(probe))
        probe.recycle()
        l = layout(spanned, p, width)
        TextLayoutSpan.applyTo(spanned, l)
        return l
    }

    private fun loadImage(path: String, maxW: Int): Bitmap? = runCatching {
        if (!File(path).exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (bmp.width <= maxW) bmp
        else Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true).also { if (it != bmp) bmp.recycle() }
    }.getOrNull()

    private fun imageBlock(bmp: Bitmap, right: Boolean, gap: Int): Block = Block(bmp.height + gap) { c ->
        val x = if (right) (W - PAD - bmp.width).toFloat() else PAD.toFloat()
        c.drawBitmap(bmp, x, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun messageBlocks(ctx: Context, mw: Markwon, m: ChatMessage): List<Block> {
        val blocks = mutableListOf<Block>()
        val body = paint(40f, INK)
        val small = paint(30f, MUTED)
        if (m.role == "user") {
            m.attachments.filter { it.kind == "image" }.take(4).forEach { a ->
                loadImage(a.path, 520)?.let { blocks += imageBlock(it, right = true, gap = 16) }
            }
            val files = m.attachments.filter { it.kind == "file" }
            val text = (if (files.isNotEmpty()) "📎 " + files.joinToString("、") { it.name } + "\n" else "") + m.content.trim()
            if (text.isNotBlank()) {
                val maxW = (W - PAD * 2) * 4 / 5
                val l = layout(text.trim(), body, maxW)
                val textW = (0 until l.lineCount).maxOf { l.getLineWidth(it) }.toInt().coerceAtMost(maxW)
                val bw = textW + 64
                val bh = l.height + 44
                blocks += Block(bh) { c ->
                    val left = (W - PAD - bw).toFloat()
                    c.drawRoundRect(RectF(left, 0f, (W - PAD).toFloat(), bh.toFloat()), 40f, 40f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUBBLE })
                    c.save(); c.translate(left + 32f, 22f); l.draw(c); c.restore()
                }
            }
        } else {
            val label = layout("Jack AI" + if (m.model.isNotEmpty()) " · " + modelLabel(m.model) else "", paint(30f, ACCENT, bold = true), W - PAD * 2)
            blocks += Block(label.height + 14) { c -> c.save(); c.translate(PAD.toFloat(), 0f); label.draw(c); c.restore() }
            val md = m.content.trim().ifEmpty { m.error?.let { "（出错：$it）" } ?: "" }
            if (md.isNotEmpty()) {
                val l = markdownLayout(mw, md, body, W - PAD * 2)
                blocks += Block(l.height) { c -> c.save(); c.translate(PAD.toFloat(), 0f); l.draw(c); c.restore() }
            }
            m.files.forEach { f ->
                if (f.mime.startsWith("image/")) {
                    loadImage(f.path, W - PAD * 2)?.let { blocks += Block(16) {}; blocks += imageBlock(it, right = false, gap = 0) }
                } else {
                    val l = layout("📄 " + f.name, small, W - PAD * 2)
                    blocks += Block(l.height + 8) { c -> c.save(); c.translate(PAD.toFloat(), 8f); l.draw(c); c.restore() }
                }
            }
        }
        return blocks
    }

    fun renderImage(ctx: Context, conv: Conversation): Bitmap {
        val mw = markwon(ctx)
        val gap = 48
        // 从最新的消息往前排，超过最大高度就停
        val perMsg = mutableListOf<List<Block>>()
        var total = 0
        val headerReserve = 260
        for (m in conv.messages.asReversed()) {
            if (m.role != "user" && m.content.isBlank() && m.error == null && m.files.isEmpty()) continue
            val b = messageBlocks(ctx, mw, m)
            val h = b.sumOf { it.height } + gap
            if (perMsg.isNotEmpty() && total + h + headerReserve > MAX_H) break
            perMsg += b
            total += h
        }
        perMsg.reverse()
        val skipped = conv.messages.count { it.role == "user" || it.content.isNotBlank() } - perMsg.size

        val title = layout(conv.title, paint(54f, INK, bold = true), W - PAD * 2)
        val sub = layout(
            "导出于 " + TIME.format(Date()) + if (skipped > 0) " · 只包含最近 ${perMsg.size} 条消息" else "",
            paint(30f, MUTED), W - PAD * 2,
        )
        val footer = layout("由 Jack AI 导出", paint(28f, MUTED), W - PAD * 2)
        val headerH = PAD + title.height + 12 + sub.height + 40
        val height = (headerH + total + 24 + footer.height + PAD).coerceAtMost(MAX_H + 400)

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        var y = PAD.toFloat()
        c.save(); c.translate(PAD.toFloat(), y); title.draw(c); c.restore()
        y += title.height + 12
        c.save(); c.translate(PAD.toFloat(), y); sub.draw(c); c.restore()
        y += sub.height + 20
        c.drawRect(PAD.toFloat(), y, (W - PAD).toFloat(), y + 2, Paint().apply { color = 0xFFE5DFD7.toInt() })
        y += 20
        for (blocks in perMsg) {
            for (b in blocks) {
                c.save(); c.translate(0f, y); b.draw(c); c.restore()
                y += b.height
            }
            y += gap
        }
        c.save(); c.translate(PAD.toFloat(), (height - PAD - footer.height).toFloat())
        footer.draw(c)
        c.restore()
        return bmp
    }
}
