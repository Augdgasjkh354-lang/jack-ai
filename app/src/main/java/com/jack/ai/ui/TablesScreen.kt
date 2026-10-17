package com.jack.ai.ui

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jack.ai.data.Table
import com.jack.ai.data.Tables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 表格技能存的表：列表 → 点开看内容，可以分享成 CSV（Excel 能打开）或删除。数据由 AI 在聊天里增删改。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Table?>(null) }
    val tables by produceState(emptyList<Table>(), version) { value = withContext(Dispatchers.IO) { Tables.list(ctx) } }
    val current = tables.firstOrNull { it.name == open }
    fun share(t: Table) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Tables.file(ctx, t.name))
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, t.name)) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.name ?: "表格") },
                navigationIcon = {
                    IconButton(onClick = { if (current != null) open = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { share(current) }) { Icon(Icons.Default.Share, "分享") }
                        IconButton(onClick = { deleting = current }) { Icon(Icons.Default.Delete, "删除") }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tables.isEmpty()) item {
                    Text("还没有表格。在聊天里说“记一下午饭 25”之类的，AI 会帮你建表记下来。",
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
                }
                items(tables, key = { it.name }) { t ->
                    Card(Modifier.fillMaxWidth().clickable { open = t.name }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text("${t.rows.size} 行 · " + t.columns.joinToString("、"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            TableGrid(current, Modifier.padding(padding))
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${t.name}」？") },
            text = { Text("共 ${t.rows.size} 行，删了找不回来。") },
            confirmButton = {
                TextButton(onClick = { Tables.delete(ctx, t.name); deleting = null; open = null; version++ }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 表格内容：左右能滑，第一列是行号，最新的在最下面。 */
@Composable
private fun TableGrid(t: Table, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val h = rememberScrollState()
    val width = 120.dp
    LazyColumn(modifier.fillMaxSize().horizontalScroll(h), contentPadding = PaddingValues(12.dp)) {
        item {
            Row(Modifier.border(0.5.dp, colors.outlineVariant)) {
                Text("#", Modifier.width(40.dp).padding(8.dp), style = MaterialTheme.typography.labelMedium, color = colors.outline)
                t.columns.forEach { c ->
                    Text(c, Modifier.width(width).padding(8.dp), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        items(t.rows.size) { i ->
            Row(Modifier.border(0.5.dp, colors.outlineVariant), verticalAlignment = Alignment.Top) {
                Text("${i + 1}", Modifier.width(40.dp).padding(8.dp), style = MaterialTheme.typography.bodySmall, color = colors.outline)
                t.rows[i].forEach { v ->
                    Text(v, Modifier.width(width).padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}
