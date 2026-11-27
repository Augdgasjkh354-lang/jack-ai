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
import com.jack.ai.data.office.PdfWriter

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
                append("**Kavi**")
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

    /** 生成导出文件，放在缓存目录里（每次导出前清掉旧的）。[image] 是长图的样式和内容选项。 */
    fun export(ctx: Context, conv: Conversation, format: Format, image: ImageOptions = ImageOptions()): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val base = conv.title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(40).ifBlank { "对话" }
        val file = File(dir, "$base.${format.ext}")
        FileOutputStream(file).use { out ->
            when (format) {
                Format.MARKDOWN -> out.write(markdown(conv).toByteArray())
                Format.PDF -> PdfWriter.write(markdown(conv), out)
                Format.IMAGE -> renderImage(ctx, conv, image).let { bmp ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                }
            }
        }
        return file
    }

    // ---------- 长图 ----------

    /** 长图的样子。思路参考 DSH Go 的分享图（只借思路）。 */
    enum class Look(val label: String) { LIGHT("浅色"), DARK("深色"), PAPER("纸张"), CARD("卡片") }

    /**
     * 长图的选项：[lastTurn] 只要最后一问一答；[questions] 带上自己问的话；[model] 回答上写是哪个模型。
     */
    data class ImageOptions(
        val look: Look = Look.LIGHT,
        val lastTurn: Boolean = false,
        val questions: Boolean = true,
        val model: Boolean = true,
    )

    /** 一种样子的颜色。卡片款的内容画在白卡片上，外面另垫一层淡彩渐变。 */
    private class Palette(val bg: Int, val ink: Int, val muted: Int, val bubble: Int, val code: Int, val rule: Int, val serif: Boolean = false)

    private fun palette(look: Look) = when (look) {
        Look.LIGHT, Look.CARD -> Palette(0xFFFFFFFF.toInt(), 0xFF0A0A0A.toInt(), 0xFF6B6B6B.toInt(), 0xFFF1F1F1.toInt(), 0xFFF2F2F2.toInt(), 0xFFE8E8E8.toInt())
        Look.DARK -> Palette(0xFF111113.toInt(), 0xFFF2F2F4.toInt(), 0xFF9A9AA0.toInt(), 0xFF2A2A2E.toInt(), 0xFF1F1F22.toInt(), 0xFF2C2C2E.toInt())
        Look.PAPER -> Palette(0xFFF6F1E7.toInt(), 0xFF2B2620.toInt(), 0xFF857B6C.toInt(), 0xFFEAE2D2.toInt(), 0xFFECE5D8.toInt(), 0xFFDDD3C1.toInt(), serif = true)
    }

    private const val W = 1080
    private const val PAD = 56
    private const val MAX_H = 24_000 // 再长手机内存吃不消，只保留最近的部分
    /** 卡片款：卡片四周留的边和圆角。 */
    private const val CARD_MARGIN = 56
    private const val CARD_RADIUS = 48f

    /** 一块内容：高度 + 画法（画在 y=0 开始的位置）。 */
    private class Block(val height: Int, val draw: (Canvas) -> Unit)

    /** 画一张图时用的东西：宽度、颜色、Markdown 排版器。 */
    private class Page(ctx: Context, val pal: Palette, val width: Int) {
        val mw: Markwon = Markwon.builder(ctx)
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeBackgroundColor(pal.code).codeBlockBackgroundColor(pal.code)
                        .codeTextColor(pal.ink).codeBlockTextColor(pal.ink)
                }
            })
            .build()

        fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            val face = if (pal.serif) Typeface.SERIF else Typeface.DEFAULT
            typeface = if (bold) Typeface.create(face, Typeface.BOLD) else face
        }
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
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

    private fun imageBlock(pg: Page, bmp: Bitmap, right: Boolean, gap: Int): Block = Block(bmp.height + gap) { c ->
        val x = if (right) (pg.width - PAD - bmp.width).toFloat() else PAD.toFloat()
        c.drawBitmap(bmp, x, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun messageBlocks(pg: Page, m: ChatMessage, showModel: Boolean): List<Block> {
        val blocks = mutableListOf<Block>()
        val pal = pg.pal
        val w = pg.width
        val body = pg.paint(40f, pal.ink)
        val small = pg.paint(30f, pal.muted)
        if (m.role == "user") {
            m.attachments.filter { it.kind == "image" }.take(4).forEach { a ->
                loadImage(a.path, 520)?.let { blocks += imageBlock(pg, it, right = true, gap = 16) }
            }
            val files = m.attachments.filter { it.kind == "file" }
            val text = (if (files.isNotEmpty()) "📎 " + files.joinToString("、") { it.name } + "\n" else "") + m.content.trim()
            if (text.isNotBlank()) {
                val maxW = (w - PAD * 2) * 4 / 5
                val l = layout(text.trim(), body, maxW)
                val textW = (0 until l.lineCount).maxOf { l.getLineWidth(it) }.toInt().coerceAtMost(maxW)
                val bw = textW + 64
                val bh = l.height + 44
                blocks += Block(bh) { c ->
                    val left = (w - PAD - bw).toFloat()
                    c.drawRoundRect(RectF(left, 0f, (w - PAD).toFloat(), bh.toFloat()), 40f, 40f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.bubble })
                    c.save(); c.translate(left + 32f, 22f); l.draw(c); c.restore()
                }
            }
        } else {
            val label = layout("Kavi" + if (showModel && m.model.isNotEmpty()) " · " + modelLabel(m.model) else "", pg.paint(30f, pal.ink, bold = true), w - PAD * 2)
            blocks += Block(label.height + 14) { c -> c.save(); c.translate(PAD.toFloat(), 0f); label.draw(c); c.restore() }
            val md = m.content.trim().ifEmpty { m.error?.let { "（出错：$it）" } ?: "" }
            if (md.isNotEmpty()) {
                val l = markdownLayout(pg.mw, md, body, w - PAD * 2)
                blocks += Block(l.height) { c -> c.save(); c.translate(PAD.toFloat(), 0f); l.draw(c); c.restore() }
            }
            m.files.forEach { f ->
                if (f.mime.startsWith("image/")) {
                    loadImage(f.path, w - PAD * 2)?.let { blocks += Block(16) {}; blocks += imageBlock(pg, it, right = false, gap = 0) }
                } else {
                    val l = layout("📄 " + f.name, small, w - PAD * 2)
                    blocks += Block(l.height + 8) { c -> c.save(); c.translate(PAD.toFloat(), 8f); l.draw(c); c.restore() }
                }
            }
        }
        return blocks
    }

    /** 按选项挑要画的消息：只要最后一轮时从最后一句提问起；不带提问就去掉自己说的话。 */
    internal fun pickMessages(messages: List<ChatMessage>, opts: ImageOptions): List<ChatMessage> {
        var list = messages
        if (opts.lastTurn) {
            val from = list.indexOfLast { it.role == "user" }
            list = if (from >= 0) list.drop(from) else list.takeLast(1)
        }
        if (!opts.questions) list = list.filter { it.role != "user" }
        return list
    }

    fun renderImage(ctx: Context, conv: Conversation, opts: ImageOptions = ImageOptions()): Bitmap {
        val card = opts.look == Look.CARD
        val pg = Page(ctx, palette(opts.look), if (card) W - CARD_MARGIN * 2 else W)
        val page = renderPage(pg, conv, opts, if (card) MAX_H / 2 else MAX_H)
        if (!card) return page
        // 卡片款：淡彩渐变的底上放一张圆角白卡片，带一点阴影
        val out = Bitmap.createBitmap(W, page.height + CARD_MARGIN * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, W.toFloat(), out.height.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, W.toFloat(), out.height.toFloat(),
                0xFFE6EBF7.toInt(), 0xFFF6ECEF.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        val rect = RectF(CARD_MARGIN.toFloat(), CARD_MARGIN.toFloat(), (W - CARD_MARGIN).toFloat(), (CARD_MARGIN + page.height).toFloat())
        c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pg.pal.bg; setShadowLayer(36f, 0f, 10f, 0x22000000)
        })
        c.save(); c.translate(rect.left, rect.top)
        c.drawRoundRect(RectF(0f, 0f, rect.width(), rect.height()), CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(page, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        })
        c.restore()
        page.recycle()
        return out
    }

    private fun renderPage(pg: Page, conv: Conversation, opts: ImageOptions, maxH: Int): Bitmap {
        val pal = pg.pal
        val w = pg.width
        val gap = 48
        val chosen = pickMessages(conv.messages, opts)
        // 从最新的消息往前排，超过最大高度就停
        val perMsg = mutableListOf<List<Block>>()
        var total = 0
        val headerReserve = 260
        var shown = 0
        for (m in chosen.asReversed()) {
            if (m.role != "user" && m.content.isBlank() && m.error == null && m.files.isEmpty()) continue
            val b = messageBlocks(pg, m, opts.model)
            val h = b.sumOf { it.height } + gap
            if (perMsg.isNotEmpty() && total + h + headerReserve > maxH) break
            perMsg += b
            total += h
            shown++
        }
        perMsg.reverse()
        val skipped = chosen.count { it.role == "user" || it.content.isNotBlank() } - shown

        val title = layout(conv.title, pg.paint(54f, pal.ink, bold = true), w - PAD * 2)
        val sub = layout(
            TIME.format(Date()) + if (skipped > 0) " · 只包含最近 $shown 条消息" else "",
            pg.paint(30f, pal.muted), w - PAD * 2,
        )
        val footer = layout("由 Kavi 导出", pg.paint(28f, pal.muted), w - PAD * 2)
        val headerH = PAD + title.height + 12 + sub.height + 40
        val height = (headerH + total + 24 + footer.height + PAD).coerceAtMost(maxH + 400)

        val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pal.bg)
        var y = PAD.toFloat()
        c.save(); c.translate(PAD.toFloat(), y); title.draw(c); c.restore()
        y += title.height + 12
        c.save(); c.translate(PAD.toFloat(), y); sub.draw(c); c.restore()
        y += sub.height + 20
        c.drawRect(PAD.toFloat(), y, (w - PAD).toFloat(), y + 2, Paint().apply { color = pal.rule })
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
