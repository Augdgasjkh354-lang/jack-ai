package com.jack.ai.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserQueue.State
import com.jack.ai.data.BrowserDownloads

/** 浏览器面板和侧边栏下载页共用同一套进度和按钮。 */
@Composable
fun BrowserDownloadRows(includeCompleted: Boolean = true) {
    val ctx = LocalContext.current
    BrowserDownloads.tasks.filter { it.state != State.CANCELLED && (includeCompleted || it.state != State.COMPLETE) }.asReversed().forEach { t ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(t.request.name.ifBlank { "下载文件" }, style = MaterialTheme.typography.bodyMedium)
            Text(when (t.state) {
                State.QUEUED -> "排队"
                State.RUNNING -> "下载中 ${BrowserDownload.sizeText(t.got)} / ${if (t.total > 0) BrowserDownload.sizeText(t.total) else "未知大小"} · ${BrowserDownload.sizeText(t.speed)}/秒"
                State.PAUSED -> "暂停 · ${BrowserDownload.sizeText(t.control.offset)}"
                State.COMPLETE -> "完成"
                State.FAILED -> "失败：${t.message}"
                State.CANCELLED -> "已取消"
            }, style = MaterialTheme.typography.bodySmall)
            if (t.message.isNotEmpty() && t.state != State.FAILED) Text(t.message, style = MaterialTheme.typography.bodySmall)
            Row {
                if (t.state in listOf(State.RUNNING, State.QUEUED)) TextButton(enabled = t.pausable, onClick = { BrowserDownloads.pause(t) }) { Text("暂停") }
                if (t.state == State.PAUSED) TextButton(onClick = { BrowserDownloads.resume(t) }) { Text("继续") }
                if (t.state != State.COMPLETE) TextButton(onClick = { BrowserDownloads.cancel(t) }) { Text("取消") }
                t.saved?.let { s ->
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { android.widget.Toast.makeText(ctx, "手机上没有能打开这个文件的 App", android.widget.Toast.LENGTH_SHORT).show() }
                    }) { Text("打开") }
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(s.mime).putExtra(Intent.EXTRA_STREAM, s.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文件")) }
                    }) { Text("分享") }
                }
            }
        }
    }
}

@Composable
fun BrowserDownloadsPanel(close: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row { TextButton(onClick = close) { Text("收起") }; Text("下载", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { BrowserDownloadRows() }
        }
    }
}
