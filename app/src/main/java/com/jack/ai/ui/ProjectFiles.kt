package com.jack.ai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.Project
import com.jack.ai.data.ProjectTree
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 项目页的「文件」：这个项目的文件夹（项目里每个对话、Kavi、终端共用的那一个）。
 * 文件树、新建、改名、删除；代码和文字用编辑器打开，网页一键预览，空的时候能从模板起步，也能直接开终端。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectFiles(project: Project, bars: PaddingValues, onTerminal: (String) -> Unit) {
    val ctx = LocalContext.current
    val key = "p_${project.id}"
    val root = remember(project.id) { Workspace.dir(ctx, key) }
    var open by remember(project.id) { mutableStateOf(setOf<String>()) }
    var rev by remember { mutableStateOf(0) }
    val entries by produceState(emptyList<ProjectTree.Entry>(), root, open, rev) {
        value = withContext(Dispatchers.IO) { ProjectTree.list(root, open) }
    }
    var editing by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var acting by remember { mutableStateOf<ProjectTree.Entry?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var templates by remember { mutableStateOf(false) }
    val openOther = rememberFileOpener()
    val web = remember(entries) { ProjectTree.webEntry(root) }

    fun openFile(f: File) = when {
        f.extension.lowercase() in setOf("html", "htm") -> preview = f
        Editable.ok(f) -> editing = f
        else -> openOther(GeneratedFile(f.name, f.path, java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream", f.length()))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = 12.dp, end = 12.dp, top = bars.calculateTopPadding() + 8.dp, bottom = bars.calculateBottomPadding() + 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("＋ 文件", false) { naming = Naming(root, folder = false) }
                Pill("＋ 文件夹", false) { naming = Naming(root, folder = true) }
                if (com.jack.ai.Edition.linux) Pill(">_ 终端", false) { onTerminal(key) }
                web?.let { w -> Pill("▶ 预览", false) { preview = w } }
            }
        }
        if (entries.isEmpty()) item {
            Column(Modifier.padding(vertical = 24.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("项目文件夹还是空的", style = MaterialTheme.typography.titleMedium)
                Text("Kavi 在这个项目里做出来的文件、终端里建的文件都在这里。可以从模板开始：", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProjectTree.Template.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall)
                            Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { t.apply(root); rev++ }) { Text("用这个") }
                    }
                }
            }
        }
        items(entries, key = { it.rel }) { e ->
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (e.dir) open = if (e.rel in open) open - e.rel else open + e.rel else openFile(e.file) },
                    onLongClick = { acting = e },
                ).padding(start = (8 + e.depth * 16).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (e.dir) (if (e.rel in open) "▾ 📂" else "▸ 📁") else "   " + icon(e.file), Modifier.width(48.dp))
                Text(e.file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (!e.dir) Text(size(e.file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (entries.isNotEmpty()) item {
            Text("长按文件或文件夹：改名、删除、在里面新建", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(12.dp))
        }
    }

    editing?.let { f -> CodeEditor(f) { editing = null; rev++ } }
    preview?.let { f -> AppViewer(f) { preview = null } }
    acting?.let { e ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(e.file.name) },
            text = {
                Column {
                    if (e.dir) {
                        MenuRow("在里面新建文件", glyph = "＋") { acting = null; naming = Naming(e.file, false) }
                        MenuRow("在里面新建文件夹", glyph = "＋") { acting = null; naming = Naming(e.file, true) }
                    } else MenuRow("打开", glyph = "↗") { acting = null; openFile(e.file) }
                    MenuRow("改名", glyph = "✎") { acting = null; naming = Naming(e.file.parentFile!!, e.dir, rename = e.file) }
                    MenuRow("删除", glyph = "✕", danger = true) { acting = null; deleting = e.file }
                }
            },
            confirmButton = { TextButton(onClick = { acting = null }) { Text("取消") } },
        )
    }
    naming?.let { n ->
        var name by remember(n) { mutableStateOf(n.rename?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (n.rename != null) "改名" else if (n.folder) "新建文件夹" else "新建文件") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(if (n.folder) "例如：src" else "例如：main.py") })
                    if (name.isNotBlank() && !ProjectTree.okName(name)) Text("名字里不能有 / \\ : * ? \" < > |，也不能以点开头",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = ProjectTree.okName(name), onClick = {
                    val made = if (n.rename != null) ProjectTree.rename(n.rename, name) else ProjectTree.create(n.parent, name, n.folder)
                    if (made == null) android.widget.Toast.makeText(ctx, "已经有叫「$name」的了", android.widget.Toast.LENGTH_SHORT).show()
                    else {
                        naming = null
                        // 在哪建的就把哪展开；新建的文件直接打开编辑
                        val rel = n.parent.relativeTo(root).path
                        if (rel.isNotEmpty()) open = open + rel
                        if (n.rename == null && !n.folder && Editable.ok(made)) editing = made
                        rev++
                    }
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("取消") } },
        )
    }
    deleting?.let { f ->
        ConfirmDeleteDialog("删除「${f.name}」？", if (f.isDirectory) "里面的文件都会一起删掉，无法恢复。" else "删掉就找不回来了。",
            onDismiss = { deleting = null }) {
            f.deleteRecursively(); deleting = null; rev++
        }
    }
}

private data class Naming(val parent: File, val folder: Boolean, val rename: File? = null)

private fun icon(f: File) = when (f.extension.lowercase()) {
    "py" -> "🐍"; "js", "mjs", "ts", "jsx", "tsx", "json" -> "📜"; "html", "htm" -> "🌐"; "css", "scss" -> "🎨"
    "md", "txt" -> "📝"; "png", "jpg", "jpeg", "gif", "webp", "svg" -> "🖼"; "sh" -> "⚙"; else -> "📄"
}

private fun size(n: Long) = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
}
