package com.jack.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.ChatExport
import com.jack.ai.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 上次选的样子：再打开时接着用（只在这次打开 App 期间记着）。 */
private var lastOptions = ChatExport.ImageOptions()

/**
 * 分享图片：选样子（浅色、深色、纸张、卡片）和要不要只分享最后一问一答、带不带提问和模型名，
 * 下面实时预览，存到相册或直接分享。思路参考 DSH Go 的分享图（只借思路）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareImageSheet(vm: ChatViewModel, conv: Conversation, onShare: (java.io.File) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var opts by remember { mutableStateOf(lastOptions) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val exporting by vm.exporting.collectAsState()
    // 导出失败时不会回调：导出一停就把「存到相册」的转圈也停了
    LaunchedEffect(exporting) { if (!exporting && saving) kotlinx.coroutines.delay(300).also { if (!exporting) saving = false } }

    // 选项一变就重画预览：整张画好再缩到一半宽，省内存
    LaunchedEffect(opts, conv.id) {
        lastOptions = opts
        rendering = true
        preview = withContext(Dispatchers.IO) {
            runCatching {
                val full = ChatExport.renderImage(ctx, conv, opts)
                val small = android.graphics.Bitmap.createScaledBitmap(full, full.width / 2, (full.height / 2).coerceAtLeast(1), true)
                if (small != full) full.recycle()
                small.asImageBitmap()
            }.getOrNull()
        }
        rendering = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("分享图片", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GlassTabs(ChatExport.Look.entries.map { it.label }, opts.look.ordinal) { opts = opts.copy(look = ChatExport.Look.entries[it]) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(opts.lastTurn, { opts = opts.copy(lastTurn = !opts.lastTurn) }, label = { Text("只要最后一问一答") })
                FilterChip(opts.questions, { opts = opts.copy(questions = !opts.questions) }, label = { Text("带上提问") })
                FilterChip(opts.model, { opts = opts.copy(model = !opts.model) }, label = { Text("写上模型") })
            }
            // 预览：长图在框里上下滑着看
            Box(Modifier.fillMaxWidth().height(380.dp).clip(Radii.l).background(colors.surfaceContainer), contentAlignment = Alignment.TopCenter) {
                preview?.let { img ->
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Image(img, "预览", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
                if (rendering) Box(Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                else if (preview == null) Box(Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) {
                    Text("画不出来：对话太长或者内存不够，试试「只要最后一问一答」", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    saving = true
                    vm.exportChat(ChatExport.Format.IMAGE, opts) { file ->
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { com.jack.ai.data.Gallery.save(ctx, file) }
                            saving = false
                            android.widget.Toast.makeText(ctx, if (name != null) "存到相册了" else "没存进相册", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.weight(1f), enabled = preview != null && !exporting, busy = saving) { Text("存到相册") }
                Button(onClick = { vm.exportChat(ChatExport.Format.IMAGE, opts) { file -> onShare(file); onDismiss() } },
                    modifier = Modifier.weight(1f), enabled = preview != null && !exporting, busy = exporting && !saving) { Text("分享") }
            }
        }
    }
}
