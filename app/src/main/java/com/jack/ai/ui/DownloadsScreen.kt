package com.jack.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.api.BrowserDownload
import com.jack.ai.data.DownloadRecord
import com.jack.ai.data.Downloads
import com.jack.ai.data.Store
import com.jack.ai.data.ToolSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 下载过的文件：最新的在上面，点开、分享、重下（从网址再下一份到手机「下载/Kavi」）、从记录里删掉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<DownloadRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val list by produceState(emptyList<Pair<DownloadRecord, Boolean>>(), version) {
        value = withContext(Dispatchers.IO) { Store(ctx).loadDownloads().asReversed().map { it to exists(ctx, it) } }
    }
    val shown = list.filter { query.isBlank() || it.first.name.contains(query.trim(), true) || it.first.url.contains(query.trim(), true) }

    fun redo(r: DownloadRecord) {
        message = "正在重下「${r.name}」…"
        scope.launch {
            message = try {
                val p = BrowserDownload.Pending(r.url, r.name, r.mime, -1, JackBrowser.userAgent(), "")
                val cookie = JackBrowser.cookieFor(r.url)
                val s = withContext(Dispatchers.IO) {
                    var t: Downloads.Target? = null
                    BrowserDownload.download(p, cookie, JackBrowser.routeFor(ctx), BrowserDownload.HARD_LIMIT,
                        { n, mime -> Downloads.toPhone(ctx, n, mime).also { t = it }.sink })
                    t!!.finish().also { Downloads.log(ctx, it, r.url) }
                }
                version++
                "「${s.name}」已存到${s.where}"
            } catch (e: Exception) {
                "没下成：" + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (list.size > 8) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜文件名或网址") }, singleLine = true)
            message?.let {
                Text(it, Modifier.fillMaxWidth().clickable { message = null }.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有下载过东西。\n在聊天里给 Kavi 一个网址，或者在浏览器里点下载。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(32.dp))
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(shown, key = { it.first.at.toString() + it.first.uri }) { (r, ok) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ok) { open(ctx, r) { message = it } }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                listOfNotNull(
                                    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(r.at)),
                                    r.size.takeIf { it > 0 }?.let { ToolSizes.text(it) },
                                    if (ok) r.where else "文件已经不在了",
                                ).joinToString(" · "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.End) {
                            if (ok) IconButton(onClick = { share(ctx, r) }) { Icon(Icons.Default.Share, "分享") }
                            if (r.url.isNotBlank()) IconButton(onClick = { redo(r) }) { Icon(Icons.Default.Refresh, "重下") }
                            IconButton(onClick = { deleting = r }) { Icon(Icons.Default.Delete, "删除") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉「${r.name}」？") },
            text = { Text(if (r.path != null) "从记录里删掉，文件也一起删（在对话文件夹里）。" else "从记录里删掉，文件也一起删（在${r.where}）。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { if (r.path != null) File(r.path).delete() else ctx.contentResolver.delete(Uri.parse(r.uri), null, null) }
                            Store(ctx).let { st -> st.saveDownloads(st.loadDownloads().filterNot { it.at == r.at && it.uri == r.uri }) }
                        }
                        version++
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 文件还在不在（对话被删、用户在文件管理器里删了都会没）。 */
private fun exists(ctx: Context, r: DownloadRecord): Boolean = runCatching {
    if (r.path != null) File(r.path).exists()
    else ctx.contentResolver.query(Uri.parse(r.uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
}.getOrDefault(false)

private fun uriOf(ctx: Context, r: DownloadRecord): Uri =
    r.path?.let { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(it)) } ?: Uri.parse(r.uri)

private fun open(ctx: Context, r: DownloadRecord, fail: (String) -> Unit) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, r), r.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { fail("手机上没有能打开「${r.name}」的 App") }
}

private fun share(ctx: Context, r: DownloadRecord) {
    val send = Intent(Intent.ACTION_SEND).setType(r.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uriOf(ctx, r))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, r.name)) }
}
