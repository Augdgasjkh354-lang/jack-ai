package com.jack.ai.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Face
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.Color
import com.jack.ai.api.BrowserPages
import com.jack.ai.data.ThemeMode
import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.Net
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.launch

// 浏览器的面板和弹窗：查找、标签页、稍后读、历史、书签、离线网页、脚本、出错页、长按菜单、权限、起始页（从 BrowserActivity.kt 拆出来，是 BrowserActivity 的扩展函数）。

/** 页内查找：边打字边找，上下跳，显示第几处 / 共几处。 */
@Composable
internal fun BrowserActivity.FindBar(q: String) {
    val b = JackBrowser
    val w = b.webView(this)
    LaunchedEffect(q) {
        if (q.isEmpty()) { w.clearMatches(); b.findCount = 0; b.findIndex = 0 } else w.findAllAsync(q)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = q,
            onValueChange = { finding = it },
            placeholder = { Text("在网页里找") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (q.isNotEmpty()) w.findNext(true) }),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (q.isEmpty()) "" else if (b.findCount == 0) "没找到" else "${b.findIndex + 1}/${b.findCount}",
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(enabled = b.findCount > 0, onClick = { w.findNext(false) }) { Icon(Icons.Default.KeyboardArrowUp, "上一处") }
        IconButton(enabled = b.findCount > 0, onClick = { w.findNext(true) }) { Icon(Icons.Default.KeyboardArrowDown, "下一处") }
        IconButton(onClick = { closeFind() }) { Icon(Icons.Default.Close, "关闭查找") }
    }
}

/** 标签页列表：点一个切过去，× 关掉，顶上新开一个。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserActivity.TabsPanel() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    val groups = b.groups()
    // 选着的分组被关光了：回到全部
    if (tabGroup != null && tabGroup !in groups) tabGroup = null
    // 记标签页本身、不记位置：关掉一个后列表先变短、这里晚一拍才重排，按位置去取会越界（闪退过）
    val shown = b.tabs.filter { tabGroup == null || it.group == tabGroup }
    val full = b.tabs.size >= JackBrowser.MAX_TABS
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showTabs = false }) { Icon(Icons.Default.Close, "收起") }
                Text("标签页（${b.tabs.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (b.incognitoSupported()) {
                    TextButton(enabled = !full, whyDisabled = "最多开 ${JackBrowser.MAX_TABS} 个标签页，先关掉几个", onClick = { accountPick = true }) { Text("分身") }
                    TextButton(enabled = !full, whyDisabled = "最多开 ${JackBrowser.MAX_TABS} 个标签页，先关掉几个", onClick = { showTabs = false; openInNewTab(null, incognito = true) }) { Text("隐身") }
                }
                TextButton(enabled = !full, whyDisabled = "最多开 ${JackBrowser.MAX_TABS} 个标签页，先关掉几个", onClick = { showTabs = false; openInNewTab(null) }) { Text("新标签页") }
            }
            // 分组：点一下只看这组（这时新开的标签页也进这组），长按改名、关掉整组
            androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { GroupChip("全部 ${b.tabs.size}", tabGroup == null, { tabGroup = null }, { groupMenu = "" }) }
                items(groups) { g -> GroupChip("$g ${b.tabs.count { it.group == g }}", tabGroup == g, { tabGroup = if (tabGroup == g) null else g }, { groupMenu = g }) }
                item { GroupChip("+ 分组", false, { groupName = "" to null }, null) }
            }
            HorizontalDivider()
            // 两列卡片：上面是这一页的缩略图（没拍过的写网站名），下面是标题和关闭；长按换分组
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.id }) { t ->
                    val on = t === b.tabs.getOrNull(b.current)
                    Column(
                        Modifier.clip(Radii.s)
                            .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outlineVariant, Radii.s)
                            .combinedClickable(onClick = { b.tabs.indexOf(t).takeIf { it >= 0 }?.let { switchTab(it) } }, onLongClick = { tabMove = t }),
                    ) {
                        Row(Modifier.fillMaxWidth().background(if (on) colors.primaryContainer else colors.surfaceVariant).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(t.badge + t.title.ifBlank { if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address) }, Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant)
                            IconButton(onClick = { b.closeTab(t); if (b.tabs.isEmpty()) showTabs = false }, Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "关闭这个标签页", Modifier.size(18.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(colors.surface), contentAlignment = Alignment.Center) {
                            val thumb = t.thumb
                            if (thumb != null) androidx.compose.foundation.Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                            else Text(if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address), Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
                            if (t.group.isNotBlank() && tabGroup == null) Text(t.group,
                                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(Radii.s).background(colors.secondaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    // 长按一个标签页：移到哪个分组
    tabMove?.let { t ->
        AlertDialog(
            onDismissRequest = { tabMove = null },
            title = { Text("移到分组") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    Text((if (t.group == g) "✓ " else "    ") + g, Modifier.fillMaxWidth().clickable { b.setGroup(t, g); tabMove = null }.padding(vertical = 12.dp))
                }
                Text("    新分组…", Modifier.fillMaxWidth().clickable { tabMove = null; groupName = "" to t }.padding(vertical = 12.dp))
                if (t.group.isNotBlank()) Text("    不分组", Modifier.fillMaxWidth().clickable { b.setGroup(t, ""); tabMove = null }.padding(vertical = 12.dp))
            } },
            confirmButton = { TextButton(onClick = { tabMove = null }) { Text("取消") } },
        )
    }
    // 新分组 / 改名：(原来的名字, 要放进去的标签页)；原来的名字空 = 新建
    groupName?.let { (from, t) ->
        var name by remember(from) { mutableStateOf(from) }
        AlertDialog(
            onDismissRequest = { groupName = null },
            title = { Text(if (from.isEmpty()) "新分组" else "分组改名") },
            text = { Column {
                if (from.isEmpty() && t == null) Text("当前这个标签页会放进新分组。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(20) }, singleLine = true, placeholder = { Text("比如：工作、购物") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusOnShow())
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                groupName = null
                if (from.isEmpty()) { b.setGroup(t ?: b.tab, name); tabGroup = name.trim() } else { b.renameGroup(from, name); if (tabGroup == from) tabGroup = name.trim() }
            }) { Text("好") } },
            dismissButton = { TextButton(onClick = { groupName = null }) { Text("取消") } },
        )
    }
    // 长按分组：改名、解散、关掉整组；长按「全部」：关掉所有标签页
    groupMenu?.let { g ->
        AlertDialog(
            onDismissRequest = { groupMenu = null },
            title = { Text(if (g.isEmpty()) "所有标签页" else "分组「$g」") },
            text = { Column {
                if (g.isNotEmpty()) {
                    Text("改名", Modifier.fillMaxWidth().clickable { groupMenu = null; groupName = g to null }.padding(vertical = 12.dp))
                    Text("解散分组（标签页留着）", Modifier.fillMaxWidth().clickable { groupMenu = null; b.ungroup(g) }.padding(vertical = 12.dp))
                }
                Text(if (g.isEmpty()) "关掉全部 ${b.tabs.size} 个标签页" else "关掉这组 ${b.tabs.count { it.group == g }} 个标签页",
                    Modifier.fillMaxWidth().clickable { groupMenu = null; b.closeGroup(g.ifEmpty { null }); if (g.isEmpty()) showTabs = false }.padding(vertical = 12.dp),
                    color = colors.error)
            } },
            confirmButton = { TextButton(onClick = { groupMenu = null }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserActivity.GroupChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Text(label, Modifier.clip(Radii.s).border(1.dp, if (selected) colors.primary else colors.outlineVariant, Radii.s)
        .background(if (selected) colors.primaryContainer else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface, maxLines = 1)
}

/** 选一个百分比的小弹窗（网页字号）。 */
@Composable
internal fun BrowserActivity.ZoomDialog(title: String, note: String, options: List<Int>, now: Int, close: () -> Unit, pick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                options.forEach { z ->
                    Text((if (z == now) "✓ " else "    ") + "$z%" + if (z == 100) "（原样）" else "",
                        Modifier.fillMaxWidth().clickable { close(); pick(z) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

internal fun BrowserActivity.openLater(e: BrowserPages.Later) {
    showLater = false
    JackBrowser.markLaterRead(e.url)
    open(e.url)
}

@Composable
internal fun BrowserActivity.LaterPanel() {
    val b = JackBrowser
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showLater = false }) { Icon(Icons.Default.Close, "收起") }
                Text("阅读列表", style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn {
                listOf(false to "未读", true to "已读").forEach { (read, label) ->
                    item { Box(Modifier.padding(horizontal = 16.dp)) { Section(label) } }
                    items(b.later.filter { it.read == read }, key = { it.url }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { openLater(e) }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title.ifBlank { BrowserPages.host(e.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(BrowserPages.host(e.url), style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { b.removeLater(e.url) }) { Icon(Icons.Default.Close, "删掉这条") }
                        }
                    }
                }
            }
        }
    }
}

/** 完整的历史记录：按天分组、能搜、能删单条、能清空。 */
@Composable
internal fun BrowserActivity.HistoryPanel() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    var all by remember { mutableStateOf<List<com.jack.ai.data.browser.HistoryEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { all = b.historyEntries() }
    val now = remember { System.currentTimeMillis() }
    val groups = remember(all, query) { com.jack.ai.data.browser.BrowserHistory.byDay(all.orEmpty(), query, now) }
    val time = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showHistory = false }) { Icon(Icons.Default.Close, "收起") }
                Text("历史记录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !all.isNullOrEmpty(), onClick = { confirmClear = true }) { Text("清空", color = colors.error) }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜标题或网址") }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
            when {
                all == null -> Text("正在读…", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
                groups.isEmpty() -> Text(if (query.isBlank()) "还没有浏览记录。" else "没找到。", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (day, list) ->
                        item(key = "d$day") { Box(Modifier.padding(horizontal = 16.dp)) { Section(day) } }
                        items(list, key = { "h" + it.url }) { e ->
                            Row(Modifier.fillMaxWidth().clickable { showHistory = false; open(e.url) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title.ifBlank { BrowserPages.host(e.url) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(time.format(java.util.Date(e.at)) + "  " + BrowserPages.host(e.url), style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { b.deleteHistory(e.url); all = all?.filterNot { it.url == e.url } }) { Icon(Icons.Default.Close, "删掉这条") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空历史记录？") },
        text = { Text("浏览记录和起始页的最近访问都删掉，Kavi 也翻不到了。登录状态、书签不动。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; b.clearHistory(); all = emptyList() }) { Text("清空", color = colors.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

/** 书签：搜索、删除、按文件夹看，导入别的浏览器的书签、导出给别的浏览器。 */
@Composable
internal fun BrowserActivity.MarksPanel() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    val folders = remember(b.marks) { b.marks.map { it.folder }.filter { it.isNotBlank() }.distinct() }
    val shown = remember(b.marks, query, folder) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        b.marks.filter { m -> (folder == null || m.folder == folder) &&
            words.all { w -> m.title.lowercase().contains(w) || m.url.lowercase().contains(w) || m.folder.lowercase().contains(w) } }
    }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showMarks = false }) { Icon(Icons.Default.Close, "收起") }
                Text("书签（${b.marks.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { runCatching { importMarks.launch(arrayOf("text/html", "*/*")) } }) { Text("导入") }
                TextButton(enabled = b.marks.isNotEmpty(), onClick = { runCatching { exportMarks.launch("bookmarks.html") } }) { Text("导出") }
            }
            if (b.marks.isEmpty()) {
                Text("还没有书签。网页菜单里点「加入书签」，或者从 Chrome、Edge 导入：在电脑上打开 书签管理 →「导出书签」，" +
                    "把得到的 .html 文件传到手机，再点上面的「导入」。", Modifier.padding(24.dp), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜标题、网址或文件夹") }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
            if (folders.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { androidx.compose.material3.FilterChip(folder == null, { folder = null }, { Text("全部") }) }
                items(folders) { f -> androidx.compose.material3.FilterChip(folder == f, { folder = if (folder == f) null else f }, { Text(f, maxLines = 1) }) }
            }
            if (shown.isEmpty()) Text("没找到。", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { "bm" + it.url }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { showMarks = false; open(m.url) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(BrowserPages.label(m), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (m.folder.isNotBlank() && folder == null) m.folder + " · " else "") + BrowserPages.host(m.url),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { b.removeMark(m.url) }) { Icon(Icons.Default.Close, "删掉这个书签") }
                    }
                }
            }
        }
    }
}

/** 离线网页列表：存下的整页，没网也能打开。 */
@Composable
internal fun BrowserActivity.OfflinePanel() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    val date = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault()) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showOffline = false }) { Icon(Icons.Default.Close, "收起") }
                Text("离线网页", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (b.offline.isNotEmpty()) Text("共 " + com.jack.ai.api.BrowserSiteData.sizeText(b.offline.sumOf { it.size }),
                    Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (b.offline.isEmpty()) Text("还没有。网页菜单里点「存成离线网页」，整页连图片和排版一起存下来，没网也能看。" +
                "（只存正文用「加入阅读列表」更省地方。）", Modifier.padding(24.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxSize()) {
                items(b.offline, key = { "off" + it.file }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { offlineView = p }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title.ifBlank { BrowserPages.host(p.url) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(date.format(java.util.Date(p.at)) + " · " + com.jack.ai.api.BrowserSiteData.sizeText(p.size) + " · " + BrowserPages.host(p.url),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { b.removeOffline(p) }) { Icon(Icons.Default.Close, "删掉") }
                    }
                }
            }
        }
    }
}

/**
 * 看一个离线网页：单独的网页窗口，只读本机这个文件，不联网、不跑脚本（存下的页面是死的，跑脚本也没用，还更安全）。
 * 点里面的链接回到 Kavi 浏览器在线打开。
 */
@Composable
internal fun BrowserActivity.OfflineViewer(p: BrowserPages.OfflinePage) {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    val file = remember(p) { b.offlineFile(p) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offlineView = null }) { Icon(Icons.Default.Close, "关掉") }
                Column(Modifier.weight(1f)) {
                    Text(p.title.ifBlank { BrowserPages.host(p.url) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("离线 · " + BrowserPages.host(p.url), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { offlineView = null; showOffline = false; open(p.url) }) { Text("打开原网页") }
            }
            if (!file.exists()) { Text("文件不见了。", Modifier.padding(24.dp), color = colors.onSurfaceVariant); return@Column }
            AndroidView(factory = { c ->
                android.webkit.WebView(c).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val u = request.url.toString()
                            if (BrowserPages.isWeb(u)) { offlineView = null; showOffline = false; open(u) }
                            return true
                        }
                    }
                    loadUrl(Uri.fromFile(file).toString())
                }
            }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 网页脚本：按网站自动跑的小脚本，能开关、改、删，新建或让 Kavi 写。 */
@Composable
internal fun BrowserActivity.ScriptsPanel() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    val site = BrowserPages.site(b.address)
    var ask by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showScripts = false }) { Icon(Icons.Default.Close, "收起") }
                Text("网页脚本", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { ask = "" }) { Text("让 Kavi 写") }
                TextButton(onClick = {
                    editScript = com.jack.ai.api.UserScripts.Script(java.util.UUID.randomUUID().toString().take(8), "", listOfNotNull(site.ifEmpty { null }), "", at = System.currentTimeMillis())
                }) { Text("新建") }
            }
            Text("打开对得上的网站时自动跑，比如自动展开全文、去掉烦人的弹层、把价格换算成人民币。能直接贴油猴脚本（GM_addStyle、GM_getValue 这类常用的能用，复杂的可能不行）。" +
                "脚本和网页自己的脚本一样，只能动这个网页，碰不到手机上的文件。只用信得过的脚本。",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (b.scripts.isEmpty()) Text("还没有脚本。", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(b.scripts.sortedByDescending { site.isNotEmpty() && com.jack.ai.api.UserScripts.matches(it.copy(enabled = true), site) }, key = { "us" + it.id }) { sc ->
                    Row(Modifier.fillMaxWidth().clickable { editScript = sc }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.name + (if (sc.by == "kavi") "（Kavi 写的）" else ""), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (sc.name in b.tab.scriptsRan) "这页在跑 · " else "") + com.jack.ai.api.UserScripts.sitesText(sc),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.Switch(sc.enabled, { b.toggleScript(sc.id); b.rerunScripts() })
                    }
                }
            }
        }
    }
    ask?.let { text ->
        var want by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { ask = null }, title = { Text("让 Kavi 写一个网页脚本") },
            text = { Column {
                Text(if (site.isEmpty()) "想让网页自动做什么？" else "想让 $site 自动做什么？", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedTextField(want, { want = it }, Modifier.fillMaxWidth().padding(top = 8.dp).focusOnShow(), placeholder = { Text("比如：自动展开全文，去掉底部的「打开 App」") }, minLines = 2)
            } },
            confirmButton = { TextButton(enabled = want.isNotBlank(), onClick = {
                ask = null; showScripts = false
                BrowserChat.open = true
                BrowserChat.send(this@ScriptsPanel, "给" + (if (site.isEmpty()) "网页" else "这个网站（$site）") + "写一个网页脚本，用 browser 的 save_script 存下来：" + want.trim())
            }) { Text("交给 Kavi") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("取消") } },
        )
    }
}

/** 改一个网页脚本：名字、网站、代码。贴进来的油猴脚本自动读出名字和网站。 */
@Composable
internal fun BrowserActivity.ScriptEditor(start: com.jack.ai.api.UserScripts.Script) {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    var name by remember(start.id) { mutableStateOf(start.name) }
    var sites by remember(start.id) { mutableStateOf(start.sites.joinToString(", ")) }
    var code by remember(start.id) { mutableStateOf(start.code) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exists = b.scripts.any { it.id == start.id }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { editScript = null }) { Icon(Icons.Default.Close, "不改了") }
                Text(if (exists) "改网页脚本" else "新网页脚本", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (exists) TextButton(onClick = { confirmDelete = true }) { Text("删除", color = colors.error) }
                TextButton(enabled = code.isNotBlank(), onClick = {
                    val head = com.jack.ai.api.UserScripts.header(code)
                    val list = com.jack.ai.api.UserScripts.cleanSites(sites).ifEmpty { head?.second.orEmpty() }
                    if (list.isEmpty()) { b.notice = "填一下在哪些网站上跑（比如 taobao.com；所有网站填 *）"; return@TextButton }
                    b.saveScript(start.copy(name = name.ifBlank { head?.first.orEmpty() }, sites = list, code = code, at = System.currentTimeMillis()))
                    editScript = null
                    b.rerunScripts()
                }) { Text("保存") }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("名字") }, singleLine = true)
                OutlinedTextField(sites, { sites = it }, Modifier.fillMaxWidth(), label = { Text("在哪些网站上跑") }, singleLine = true,
                    supportingText = { Text("网站名，几个用逗号隔开，子域名也算；所有网站填 *。贴油猴脚本可以不填") })
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp), label = { Text("JavaScript 代码") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont))
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("删掉「${start.name}」？") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; editScript = null; b.removeScript(start.id); b.rerunScripts() }) { Text("删除", color = colors.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

/** 网页没打开：盖一层干净的说明，不露出网页组件自带的英文出错页。 */
@Composable
internal fun BrowserActivity.ErrorPage(msg: String) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("网页没打开", style = MaterialTheme.typography.titleMedium)
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reload() }) { Text("重试") }
            JackBrowser.tab.httpFallback?.let { http -> OutlinedButton(onClick = { open(JackBrowser.allowHttp(http)) }) { Text("仍然打开") } }
            val url = JackBrowser.address.ifEmpty { editing }
            if (BrowserPages.isWeb(url)) OutlinedButton(onClick = { Links.system(this@ErrorPage, url) }) { Text("用系统浏览器打开") }
        }
    }
}

/** 网页还没显示出来时盖在空白网页窗口上的一页：写着在打开哪个网站。 */
@Composable
internal fun BrowserActivity.OpeningPage(url: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(BrowserPages.host(url).ifEmpty { url.take(60) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("正在打开…", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** 长按链接或图片弹出的菜单。 */
@Composable
internal fun BrowserActivity.PressMenu(p: JackBrowser.Pressed) {
    val b = JackBrowser
    val close = { b.pressed = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (p.link != null) "链接" else "图片") },
        text = {
            Column {
                (p.link ?: p.image)?.let {
                    Text(it.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
                @Composable
                fun item(label: String, action: () -> Unit) = Text(label, Modifier.fillMaxWidth().clickable { close(); action() }.padding(vertical = 12.dp))
                p.link?.let { link ->
                    Text("稍后读", Modifier.fillMaxWidth().clickable(enabled = !b.tab.incognito) { close(); b.addLater(link) }.padding(vertical = 12.dp),
                        color = if (b.tab.incognito) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface)
                    item("打开") { open(link) }
                    item("在新标签页打开") { openInNewTab(link) }
                    item("复制链接") { copy(link); b.notice = "已复制链接" }
                    item("分享链接") { share(link, "") }
                    item("用系统浏览器打开") { Links.system(this@PressMenu, link) }
                }
                p.image?.let { img ->
                    item("识别二维码") { scanQr(img) }
                    item("下载图片") { b.downloadImage(img) }
                    if (BrowserPages.isWeb(img)) item("复制图片地址") { copy(img); b.notice = "已复制图片地址" }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("取消") } },
    )
}

/** 网页要用相机、麦克风、位置：问用户。允许 / 不允许会按网站记住（隐身页不记），「只这次」不记。 */
@Composable
internal fun BrowserActivity.PermDialog(a: JackBrowser.PermAsk) {
    val notes = buildList {
        if (BrowserPages.LOCATION in a.kinds) add("网站会知道你在哪，走代理也挡不住。")
        if ((BrowserPages.CAMERA in a.kinds || BrowserPages.MIC in a.kinds) && JackBrowser.rtcBlocked())
            add("要视频、语音通话，还得在菜单里打开「允许网页视频通话」。")
        if (!a.incognito) add("选了会记住，以后不再问；菜单里能重置。")
    }
    AlertDialog(
        onDismissRequest = { a.answer(false, false) },
        title = { Text("「${a.site}」想用你的${BrowserPages.kindsText(a.kinds)}") },
        text = { Text(notes.joinToString("\n"), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Row {
                if (!a.incognito) TextButton(onClick = { a.answer(true, false) }) { Text("只这次") }
                TextButton(onClick = { a.answer(true, true) }) { Text("允许") }
            }
        },
        dismissButton = { TextButton(onClick = { a.answer(false, true) }) { Text("不允许") } },
    )
}

/** Kavi 是不是手机的默认浏览器。 */
internal fun BrowserActivity.isDefaultBrowser(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    else packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == packageName

/** 设成默认浏览器：新系统弹系统自己的确认框，旧系统打开「默认应用」设置。 */
internal fun BrowserActivity.makeDefault() {
    val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_BROWSER) }?.createRequestRoleIntent(RoleManager.ROLE_BROWSER) else null
    val intent = role ?: Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    runCatching { askDefault.launch(intent) }.onFailure { JackBrowser.notice = "打不开系统设置。可以到 设置 → 应用 → 默认应用 → 浏览器 里选 Kavi。" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserActivity.StartPage() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    // 常用网站：书签在前，不够八个用默认的补上（同一个网站不重复）
    val tiles = remember(b.marks) {
        val defaults = listOf("Google" to "https://www.google.com", "YouTube" to "https://m.youtube.com", "B站" to "https://m.bilibili.com",
            "淘宝" to "https://main.m.taobao.com", "京东" to "https://m.jd.com", "知乎" to "https://www.zhihu.com",
            "GitHub" to "https://github.com", "百度" to "https://www.baidu.com")
        val seen = HashSet<String>()
        (b.marks.map { BrowserPages.label(it) to it.url } + defaults)
            .filter { (_, u) -> seen.add(com.jack.ai.api.BrowserSiteData.root(BrowserPages.host(u))) }.take(8)
    }
    LazyColumn(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Kavi", style = MaterialTheme.typography.headlineLarge, color = colors.primary)
                // 大搜索框：点了就是地址栏（地址栏在底部时，键盘弹起来正好在手边）
                Row(Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                    .background(colors.surfaceVariant).clickable { runCatching { addressFocus.requestFocus() } }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Text("搜索或输入网址", Modifier.weight(1f).padding(start = 12.dp), color = colors.onSurfaceVariant)
                    IconButton(onClick = { startVoice() }) { Icon(AppIcons.Mic, "语音输入", Modifier.size(22.dp)) }
                    IconButton(onClick = { startScan() }) { Icon(AppIcons.QrScan, "扫二维码", Modifier.size(22.dp)) }
                }
            }
        }
        item {
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, url) ->
                        Column(Modifier.weight(1f).clip(Radii.s).clickable { open(url) }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            val host = BrowserPages.host(url)
                            Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.hsv((host.hashCode().mod(360)).toFloat(), 0.45f, 0.78f)), contentAlignment = Alignment.Center) {
                                Text(WebShortcut.letter(label, url), color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(label, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val unread = b.later.filterNot { it.read }
        if (unread.isNotEmpty()) {
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("阅读列表") }
                TextButton(onClick = { showLater = true }) { Text("全部") }
            } }
            items(unread.take(5), key = { "l" + it.url }) { e ->
                Column(Modifier.fillMaxWidth().clickable { openLater(e) }.padding(vertical = 8.dp)) {
                    Text(e.title.ifBlank { BrowserPages.host(e.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(BrowserPages.host(e.url), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Section("书签") }
            TextButton(onClick = { showMarks = true }) { Text(if (b.marks.isEmpty()) "导入" else "全部（${b.marks.size}）") }
        } }
        items(b.marks.drop(8).take(6), key = { "m" + it.url }) { PageRow(it) }
        if (b.visits.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Section("最近访问") }
                    TextButton(onClick = { showHistory = true }) { Text("全部历史") }
                }
            }
            items(b.visits.take(8), key = { "v" + it.url }) { PageRow(it) }
        }
        item {
            Column(Modifier.padding(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LaunchedEffect(Unit) { defaultBrowser = isDefaultBrowser() }
                if (!defaultBrowser) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("别的 App 里点网页链接时直接用 Kavi 打开", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    TextButton(onClick = { makeDefault() }) { Text("设为默认浏览器") }
                }
                Text(
                    "开着 Kavi 专用代理时，国外网站自动走代理，不用开系统 VPN。Kavi 也能用这个浏览器替你操作网页，你在这里登录过的网站它也能用。" +
                        "这里的登录状态和手机浏览器不共用。不能用来登录 Google 账号（Google 不允许）。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun BrowserActivity.Section(name: String) =
    Text(name, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
internal fun BrowserActivity.PageRow(v: BrowserPages.Visit) {
    Column(Modifier.fillMaxWidth().clickable { open(v.url) }.padding(vertical = 8.dp)) {
        Text(BrowserPages.label(v), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(BrowserPages.host(v.url), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
