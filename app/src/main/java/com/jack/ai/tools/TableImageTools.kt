package com.jack.ai.tools

import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.TableGrid
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * read_table_image：把照片、截图里的表格认出来，按位置排回行和列，交给模型核对后做成 Excel。
 * 用手机上的离线文字识别（ML Kit 中文），数字比让模型「看」更准；模型能看图时再对照原图改认错的字。
 */
internal suspend fun ToolSession.readTableImage(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("识别表格：$name") {
        val file = wsFile(name)
        if (Workspace.ext(file) !in Workspace.IMAGE_EXT) throw IllegalArgumentException("「${file.name}」不是图片。PDF 先用 convert_file 转成图片")
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(app, Uri.fromFile(file)) }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val text = try {
            suspendCancellableCoroutine { c ->
                recognizer.process(image).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
        val lines = text.textBlocks.flatMap { it.lines }.map { line ->
            line.elements.mapNotNull { e ->
                val b = e.boundingBox ?: return@mapNotNull null
                TableGrid.Box(e.text, b.left, b.top, b.right, b.bottom, e.confidence)
            }
        }
        val r = withContext(Dispatchers.Default) { TableGrid.build(lines) }
        if (r.rows.isEmpty()) throw IllegalArgumentException("图里没认出字：可能太模糊、太暗或者是手写的，请用户重拍一张（正对着、光线够、整张表都在画面里）")
        val cols = r.rows.maxOf { it.size }
        val md = r.rows.mapIndexed { i, row ->
            "| " + (0 until cols).joinToString(" | ") { row.getOrElse(it) { "" }.replace("|", "/") } + " |" + if (i == 0) "\n|" + "---|".repeat(cols) else ""
        }.joinToString("\n")
        val json = JsonArray(r.rows.map { row -> JsonArray(row.map { JsonPrimitive(it) }) }).toString()
        val note = buildString {
            append("认出 ${r.rows.size} 行 × $cols 列（第一行一般是表头）：\n").append(md).append("\n\n按行排好的数据（可以直接当 create_excel 的 rows）：\n").append(json)
            if (r.unsure.isNotEmpty()) append("\n\n这些字没把握，可能认错了：").append(r.unsure.joinToString("、"))
            append("\n\n做成 Excel 前先核对：")
            append(if (canSee) "原图在对话里，对照着看一遍，改掉认错的字和数，" else "")
            append("合计、小计行算一下对不对；空的格子是原表就空着，还是没认出来（拿不准就照空着放，告诉用户）。表头跨两行、合并单元格的，按意思理顺成一行表头。")
        }
        note to SearchRecord("识别表格：${file.name}")
    }
}
