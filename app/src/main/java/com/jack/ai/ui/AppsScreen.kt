package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.jack.ai.UiState
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 做过的所有小工具，按最近改动排；点开全屏用，也能删。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val apps by produceState(emptyList<File>(), refresh) {
        value = withContext(Dispatchers.IO) {
            Workspace.root(ctx).listFiles().orEmpty().filter { it.isDirectory }
                .flatMap { dir -> Workspace.files(dir).filter { it.extension.equals("html", true) } }
                .sortedByDescending { it.lastModified() }
        }
    }
    var open by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    /** 文件夹名是对话编号，或者 p_项目编号。 */
    fun from(f: File): String {
        val key = f.parentFile?.name.orEmpty()
        return if (key.startsWith("p_")) state.project(key.removePrefix("p_"))?.let { "项目「${it.name}」" } ?: ""
        else state.conversations.firstOrNull { it.id == key }?.let { "「${it.title}」" } ?: ""
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小工具") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (apps.isEmpty()) {
                item {
                    Text(
                        "还没有小工具。在聊天里说“做一个能调参数的贷款计算器”试试。",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            items(apps, key = { it.absolutePath }) { f ->
                Card(onClick = { open = f }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name.removeSuffix(".html"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(f.lastModified())) +
                                    from(f).let { if (it.isEmpty()) "" else " · 来自$it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Default.Delete, "删除") }
                    }
                }
            }
        }
    }
    open?.let { f -> AppViewer(f) { open = null; refresh++ } }
    deleting?.let { f ->
        ConfirmDeleteDialog("删除小工具？", "「${f.name.removeSuffix(".html")}」和它存的数据都会被删掉。", onDismiss = { deleting = null }) {
            Workspace.delete(f)
            File(f.parentFile, ".appdata/${f.name}.json").delete()
            deleting = null
            refresh++
        }
    }
}
