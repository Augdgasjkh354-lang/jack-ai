package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.UiState
import com.jack.ai.data.FileLibrary
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文件库：所有对话里你发的、Kavi 做的文件放在一处，按类型筛、按名字搜，点「去对话」回到它所在的对话。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileLibraryScreen(state: UiState, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val all by produceState<List<FileLibrary.Entry>?>(null, state.conversations.size, state.projects.size) {
        value = withContext(Dispatchers.IO) { FileLibrary.scan(Workspace.root(ctx), state.conversations, state.projects) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val list = all
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("所有对话里你发的文件、Kavi 做的文件都在这里", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 一个文件都没有：搜索框和筛选都不摆，只留空状态
            val none = list != null && list.isEmpty()
            // 和侧边栏同一个圆头搜索框（带放大镜、清空、键盘搜索键）
            if (!none) item { SearchField(query, { query = it }, placeholder = "搜文件名或对话") }
            if (!none) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == null, { kind = null }, label = { Text("全部") })
                    FileLibrary.Kind.entries.forEach { k ->
                        val n = list?.count { it.kind == k } ?: 0
                        // 再点一下选中的那个回到「全部」
                        if (n > 0) FilterChip(kind == k.name, { kind = if (kind == k.name) null else k.name }, label = { Text("${k.label} $n") })
                    }
                }
            }
            if (list == null) {
                item { Text("正在找…", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 24.dp)) }
                return@LazyColumn
            }
            val shown = FileLibrary.filter(list, kind?.let { FileLibrary.Kind.valueOf(it) }, query)
            if (none) item { EmptyNote(AppIcons.Folder, "还没有文件", "发给 Kavi 的附件、它做出来的文件都会出现在这里。") }
            else if (shown.isEmpty()) item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("没有符合的文件。", color = MaterialTheme.colorScheme.outline)
                    // 搜不到时一下清掉搜索词和类型，不用分别去删
                    TextButton(onClick = { query = ""; kind = null }) { Text("清掉搜索和筛选") }
                }
            }
            items(shown, key = { it.file.absolutePath }) { e ->
                Column {
                    GeneratedFileCard(GeneratedFile(e.file.name, e.file.absolutePath, com.jack.ai.tools.ToolSession.mimeOf(e.file.name), e.file.length()))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.place + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(e.modified),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        e.convId?.let { id -> TextButton(onClick = { onOpenChat(id) }) { Text("去对话") } }
                    }
                }
            }
        }
    }
}
