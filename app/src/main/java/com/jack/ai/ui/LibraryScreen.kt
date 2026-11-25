package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.KbRepo
import com.jack.ai.data.KnowledgeBase
import com.jack.ai.data.LibraryDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 资料库：上面是知识库（下载的、自己建的，各自开关、更新、删除），下面是「我的资料」（自己导入的）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    // 导入到哪：空的是「我的资料」，否则是某个知识库
    var importTo by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addLibraryFiles(it, importTo) }
    var deleting by remember { mutableStateOf<LibraryDoc?>(null) }
    var deletingKb by remember { mutableStateOf<KnowledgeBase?>(null) }
    var openKb by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // 点一份文档看它的正文（Kavi 检索时读的就是这份）
    var reading by remember { mutableStateOf<LibraryDoc?>(null) }
    val fmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }
    // 按意思搜备好了多少段（向量在后台慢慢补）
    var coverage by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(state.library, state.kbs) {
        coverage = withContext(Dispatchers.IO) { runCatching { vm.libraryCoverage() }.getOrNull() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资料库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { importTo = ""; pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "导入") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("知识库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // 有知识库时，新建、下载、检查更新都收成标题右边的小按钮；没有时放到下面的空状态里
                    if (state.kbs.isNotEmpty()) {
                        TextButton(onClick = { creating = true }) { Text("新建") }
                        TextButton(onClick = { catalog = true }) { Text("下载") }
                    }
                    if (state.kbs.any { it.source.isNotEmpty() }) TextButton(onClick = { vm.syncKbs() }, enabled = state.kbBusy == null) { Text("检查更新") }
                }
                state.kbBusy?.let { BusyRow(it) }
            }
            if (state.kbs.isEmpty()) {
                item {
                    EmptyNote(AppIcons.Folder, "还没有知识库",
                        "新建一个往里放文件，或者从 GitHub 下载现成的；也可以跟 Kavi 说「帮我整理一个 XX 的知识库」。") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { creating = true }) { Text("新建") }
                            OutlinedButton(onClick = { catalog = true }) { Text("从 GitHub 下载") }
                        }
                    }
                }
            }
            items(state.kbs.sortedByDescending { it.addedAt }, key = { "kb-" + it.id }) { kb ->
                val docs = state.library.filter { it.kb == kb.id }
                KbCard(kb, docs, open = openKb == kb.id, fmt = fmt,
                    onOpen = { openKb = if (openKb == kb.id) null else kb.id },
                    onToggle = { vm.toggleKb(kb.id, it) },
                    onImport = { importTo = kb.id; pick.launch(arrayOf("*/*")) },
                    onSync = { vm.syncKbs() },
                    onDelete = { deletingKb = kb },
                    onDeleteDoc = { deleting = it },
                    onReadDoc = { reading = it })
            }
            item {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的资料", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { importTo = ""; pick.launch(arrayOf("*/*")) }, enabled = !state.importing) { Text("导入文档") }
                }
                if (state.importing) BusyRow("正在读取…")
            }
            val mine = state.library.filter { it.kb.isEmpty() || state.kbs.none { k -> k.id == it.kb } }
            if (mine.isEmpty()) {
                item { EmptyNote(AppIcons.File, "还没有自己的资料", "导入文档后，Kavi 回答时会先在这里面找。", Modifier.padding(top = 0.dp)) }
            }
            items(mine.sortedByDescending { it.addedAt }, key = { it.id }) { d -> DocRow(d, fmt, onOpen = { reading = d }) { deleting = d } }
            coverage?.let { (have, all) ->
                if (all > 0) item {
                    Text(if (have >= all) "按意思搜：全部 $all 段都备好了" else "按意思搜：已备好 $have / $all 段，其余在后台慢慢算（没备好的照样能按关键词搜到）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    deleting?.let { d ->
        ConfirmDeleteDialog("从资料库删除？", "「${d.name}」会从资料库移除。" + if (d.path.isNotEmpty()) "（从 GitHub 来的，下次更新时还会下回来；不想要就关掉或删掉整个知识库）" else "",
            onDismiss = { deleting = null }) { vm.deleteLibraryDoc(d.id); deleting = null }
    }
    deletingKb?.let { kb ->
        ConfirmDeleteDialog("删掉知识库？", "「${kb.name}」和里面的 ${state.library.count { it.kb == kb.id }} 份文档都会删掉。", onDismiss = { deletingKb = null }) {
            vm.removeKb(kb.id); deletingKb = null
        }
    }
    // 建好就要往里放东西：直接打开选文件，不用再去它的菜单里找「导入文件」
    if (creating) NewKbDialog(onDismiss = { creating = false }) { name ->
        creating = false
        vm.createKb(name) { id -> importTo = id; openKb = id; runCatching { pick.launch(arrayOf("*/*")) } }
    }
    reading?.let { d ->
        TextViewer(d.name, load = { withContext(Dispatchers.IO) { vm.libraryText(d.id) } to false },
            onDismiss = { reading = null }, subtitle = "${d.chars} 字 · Kavi 检索时读的文字")
    }
    if (catalog) CatalogDialog(vm, state) { catalog = false; vm.closeKbCatalog() }
}

@Composable
private fun BusyRow(text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DocRow(d: LibraryDoc, fmt: SimpleDateFormat, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.File, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${d.chars} 字 · ${fmt.format(Date(d.addedAt))} 加入", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

@Composable
private fun KbCard(
    kb: KnowledgeBase, docs: List<LibraryDoc>, open: Boolean, fmt: SimpleDateFormat,
    onOpen: () -> Unit, onToggle: (Boolean) -> Unit, onImport: () -> Unit, onSync: () -> Unit, onDelete: () -> Unit, onDeleteDoc: (LibraryDoc) -> Unit,
    onReadDoc: (LibraryDoc) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kb.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (kb.description.isNotBlank()) Text(kb.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${docs.size} 份 · ${docs.sumOf { it.chars } / 1000} 千字 · " +
                        (if (kb.source.isEmpty()) "自己建的" else "来自 ${KbRepo.repoOf(kb.source)} · ${fmt.format(Date(kb.updatedAt))} 更新"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = kb.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (kb.source.isEmpty()) DropdownMenuItem(text = { Text("导入文件") }, onClick = { menu = false; onImport() })
                        else DropdownMenuItem(text = { Text("检查更新") }, onClick = { menu = false; onSync() })
                        DropdownMenuItem(text = { Text("删除知识库") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (open) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp).heightIn(max = 360.dp)) {
                    if (docs.isEmpty()) Text("还没有文档。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    // 文档多的知识库只列前 100 份，免得列表太长
                    docs.sortedBy { it.name }.take(100).forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(Radii.xs).clickable { onReadDoc(d) }.padding(start = 4.dp)) {
                            Text(d.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${d.chars} 字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            IconButton(onClick = { onDeleteDoc(d) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp)) }
                        }
                    }
                    if (docs.size > 100) Text("……还有 ${docs.size - 100} 份", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun NewKbDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建知识库") },
        text = {
            Column {
                Text("建好后在它的菜单里「导入文件」往里放资料。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("名字") }, singleLine = true, modifier = Modifier.focusOnShow(),
                    keyboardOptions = DoneKeyboard, keyboardActions = doneAction(name.isNotBlank()) { onCreate(name) })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 下载知识库：填 GitHub 项目，列出里面的知识库。 */
@Composable
private fun CatalogDialog(vm: ChatViewModel, state: UiState, onDismiss: () -> Unit) {
    var repo by remember { mutableStateOf("") }
    val listing = state.kbCatalog?.first
    val entries = state.kbCatalog?.second.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 GitHub 下载知识库") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(repo, { repo = it }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("GitHub 项目") }, placeholder = { Text("作者/项目名 或 网址") })
                    TextButton(onClick = { vm.loadKbCatalog(repo) }, enabled = state.kbBusy == null && repo.isNotBlank()) { Text("读取") }
                }
                state.kbBusy?.let { BusyRow(it) }
                listing?.let {
                    Text("${it.repo}（${it.branch}）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                }
                if (listing != null && entries.all { it.found.files.isEmpty() }) {
                    Text("这里没有能当资料的文件（md、txt、pdf、docx 这类）。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries.filter { it.found.files.isNotEmpty() }, key = { it.found.dir }) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                                    if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text("${e.found.files.size} 份文档" + if (e.found.skipped > 0) "（另有 ${e.found.skipped} 份超出上限不收）" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                                val upToDate = e.installed != null && e.installed.hash == e.found.hash
                                TextButton(onClick = { vm.installKb(e.found.dir) }, enabled = state.kbBusy == null && !upToDate) {
                                    Text(when { e.installed == null -> "下载"; upToDate -> "已装"; else -> "更新" })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
