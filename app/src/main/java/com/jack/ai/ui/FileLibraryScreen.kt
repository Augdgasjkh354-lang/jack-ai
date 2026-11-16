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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("搜文件名或对话") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == null, { kind = null }, label = { Text("全部") })
                    FileLibrary.Kind.entries.forEach { k ->
                        val n = list?.count { it.kind == k } ?: 0
                        if (n > 0) FilterChip(kind == k.name, { kind = k.name }, label = { Text("${k.label} $n") })
                    }
                }
            }
            if (list == null) {
                item { Text("正在找…", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 24.dp)) }
                return@LazyColumn
            }
            val shown = FileLibrary.filter(list, kind?.let { FileLibrary.Kind.valueOf(it) }, query)
            if (shown.isEmpty()) item {
                Text(if (list.isEmpty()) "还没有文件。发给 Kavi 的附件、它做出来的文件都会出现在这里。" else "没有符合的文件。",
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 24.dp))
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
