package com.jack.ai.a11y

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 控件树读不全的界面（游戏、自己画的控件、没名字的按钮）：在手机上离线识别截图里的字，补上控件树缺的那些，
 * 带上位置，模型看不了图也能按坐标点。思路来自 Mika 的文字识别补读，Apache-2.0。
 */
object ScreenOcr {
    /** 一行字和它在屏幕上的中心（屏幕像素）。 */
    data class Line(val text: String, val cx: Int, val cy: Int)

    /** 识别失败（没装好识别模型之类）就当没读到，不影响原来的读屏。 */
    suspend fun read(bmp: Bitmap): List<Line> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { c ->
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resume(null) }
            } ?: return emptyList()
            result.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                val box = l.boundingBox ?: return@mapNotNull null
                l.text.trim().takeIf { it.isNotEmpty() }?.let { Line(it, box.centerX(), box.centerY()) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        finally { recognizer.close() }
    }

    /**
     * 给模型的那段：只列控件树里没有的字（按从上到下），坐标换成截图上的像素（[k] = 屏幕像素 ÷ 截图像素），和 tap 的 x、y 一致。
     */
    fun format(lines: List<Line>, known: List<String>, k: Float): String {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }
        val have = known.map(::norm).filter { it.isNotEmpty() }
        val extra = lines.filter { l -> norm(l.text).let { t -> t.isNotEmpty() && have.none { it.contains(t) || t.contains(it) && it.length >= 2 } } }
            .sortedWith(compareBy({ it.cy }, { it.cx })).take(80)
        if (extra.isEmpty()) return ""
        return "文字识别补读到的字（控件树里没有的，[x,y] 是截图上的位置，要点就用 tap 的 x、y）：\n" +
            extra.joinToString("\n") { "· 「${it.text.take(60)}」[${(it.cx / k).toInt()},${(it.cy / k).toInt()}]" } + "\n"
    }
}
