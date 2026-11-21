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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jack.ai.ui.BrowserActivity.Tile

// 浏览器底部的条和菜单：导航条、菜单、阅读条、选字条、教学条、问 Kavi 面板，以及它们用的小动作（从 BrowserActivity.kt 拆出来，是 BrowserActivity 的扩展函数）。

/** 底栏：后退、前进、起始页、标签页、菜单，五个大按钮。长按标签页数字直接开新标签页。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserActivity.NavBar() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    @Composable
    fun androidx.compose.foundation.layout.RowScope.Btn(desc: String, enabled: Boolean = true, onLong: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.weight(1f).height(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(enabled = enabled, onClickLabel = desc, onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides
                if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.35f)) { content() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("后退", b.canBack, onClick = { b.clearError(); b.webView(this@NavBar).goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", Modifier.size(26.dp)) }
        Btn("前进", b.canForward, onClick = { b.clearError(); b.webView(this@NavBar).goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进", Modifier.size(26.dp)) }
        Btn("起始页", b.address.isNotEmpty(), onClick = { finding = null; b.home(); editing = "" }) { Icon(Icons.Default.Home, "起始页", Modifier.size(26.dp)) }
        Btn("标签页", onLong = { if (b.tabs.size < JackBrowser.MAX_TABS) openInNewTab(null) }, onClick = { b.snap(); showTabs = true }) {
            Text(b.tabs.size.coerceAtLeast(1).toString(),
                Modifier.border(2.dp, LocalContentColor.current, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelLarge)
        }
        Btn("菜单", onClick = { menu = true }) { Icon(Icons.Default.Menu, "菜单", Modifier.size(26.dp)) }
    }
}

/** 菜单：从底部拉出。上面是常用的大图标，下面按「这个网页 / Kavi / 收藏 / 设置 / 线路」分好。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BrowserActivity.MenuSheet() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    val has = b.address.isNotEmpty()
    fun go(action: () -> Unit) { menu = false; action() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { menu = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            // 这一页是什么、怎么上网（点线路在「自动」和「全部走代理」之间切）
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                if (has) Text(b.title.ifBlank { BrowserPages.host(b.address) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(routeLabel(), Modifier.clickable {
                    val next = if (b.route == BrowserNet.PROXY) BrowserNet.AUTO else BrowserNet.PROXY
                    b.changeRoute(next)
                    b.notice = if (next == BrowserNet.PROXY) "全部走代理：网站都看不到你的真实 IP（国内网站可能慢一点）" else "自动：国内直连，国外走代理"
                    if (has) reload()
                }.padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (proxiedNow() && (b.route == BrowserNet.PROXY || b.proxyForcedHere())) colors.primary else colors.onSurfaceVariant)
            }
            val marked = b.marks.any { it.url == b.address }
            val tiles = listOf(
                Tile(Icons.Default.Star, if (marked) "已加书签" else "加书签", has, marked) {
                    b.toggleMark(); b.notice = if (!marked) "已加入书签，起始页能看到" else "已移出书签"
                },
                Tile(Icons.Default.List, "书签", true) { showMarks = true },
                Tile(Icons.Default.DateRange, "历史", true) { showHistory = true },
                Tile(AppIcons.Download, "下载", true) { showDownloads = true },
                Tile(Icons.Default.Share, "分享", has) { share(b.address, b.title) },
                Tile(Icons.Default.Search, "页内查找", has) { finding = "" },
                Tile(AppIcons.File, "阅读模式", has) {
                    lifecycleScope.launch { b.readable(this@MenuSheet)?.let { reader = it } ?: run { b.notice = "这一页找不出正文（可能不是文章页，或者还没加载完）" } }
                },
                Tile(AppIcons.Globe, if (b.tab.translated) "看原文" else "翻译", has && b.tab.translating == null, b.tab.translated) {
                    if (b.tab.translated) b.showOriginal() else lifecycleScope.launch { b.translate(this@MenuSheet)?.let { b.notice = it } }
                },
                Tile(AppIcons.Speaker, if (PageReader.active) "停止朗读" else "朗读", has || PageReader.active, PageReader.active) {
                    if (PageReader.active) PageReader.stop() else readAloud()
                },
                Tile(AppIcons.Computer, "电脑版", has, has && b.isDesktopAt(b.address)) { b.toggleDesktop() },
                Tile(AppIcons.Image, "长截图", has && !shooting) { takeLongShot() },
                Tile(Icons.Default.Lock, "隐身页", b.incognitoSupported() && b.tabs.size < JackBrowser.MAX_TABS) { openInNewTab(null, incognito = true) },
            )
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).clip(Radii.s).clickable(enabled = t.enabled) { go(t.onClick) }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (t.on) colors.primaryContainer else colors.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(t.icon, null, Modifier.size(22.dp), tint = when {
                                    !t.enabled -> colors.onSurface.copy(alpha = 0.35f); t.on -> colors.onPrimaryContainer; else -> colors.onSurfaceVariant })
                            }
                            Text(t.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (t.enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.35f))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            @Composable
            fun Head(name: String) = Text(name, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
            @Composable
            fun Item(label: String, enabled: Boolean = true, value: String? = null, switch: Boolean? = null, color: Color = Color.Unspecified, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { if (switch == null) go(onClick) else onClick() }
                    .padding(horizontal = 20.dp, vertical = if (switch != null) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = if (!enabled) colors.onSurface.copy(alpha = 0.35f) else if (color != Color.Unspecified) color else colors.onSurface)
                    value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                    switch?.let { androidx.compose.material3.Switch(it, { onClick() }, enabled = enabled) }
                }
            }

            Head("这个网页")
            Item("视频倍速", has, BrowserPages.rateText(b.tab.rate)) { ratePick = true }
            Item("存成离线网页", has && !b.tab.incognito) { lifecycleScope.launch { b.notice = b.saveOffline() } }
            Item("存成 PDF", has) { savePdf() }
            Item(if (b.later.any { it.url == b.address }) "移出阅读列表" else "加入阅读列表", has && !b.tab.incognito) {
                if (b.later.any { it.url == b.address }) b.removeLater(b.address) else b.addLater(b.address, b.title)
            }
            Item("存进资料库", has) { lifecycleScope.launch { b.notice = runCatching { b.saveToLibrary(this@MenuSheet) }.getOrElse { "没存成：${it.message}" } } }
            Item("复制链接", has) { copy(b.address); b.notice = "已复制链接" }
            Item("添加到主屏幕", has && BrowserPages.isWeb(b.address) && !b.tab.incognito) {
                if (WebShortcut.supported(this@MenuSheet)) shortcutAsk = b.address to b.title.ifBlank { WebShortcut.host(b.address) }
                else b.notice = "这个桌面不支持添加网页图标。"
            }
            Item("屏蔽网页上的东西", has, b.blocksHere().size.takeIf { it > 0 }?.let { "$it 处" }) {
                if (b.blocksHere().isNotEmpty()) blockList = true else b.startPick()
            }
            Item("这个网站的数据", has && BrowserPages.isWeb(b.address)) {
                lifecycleScope.launch { b.siteData()?.let { siteData = it } ?: run { b.notice = "看不出这是哪个网站" } }
            }
            if (has && b.permsHere()) Item("重置这个网站的权限") { b.resetPermsHere(); b.notice = "这个网站下次要用相机、麦克风或位置时会再问你。" }
            Item("用系统浏览器打开", has) { Links.system(this@MenuSheet, b.address) }
            Item((if (b.tab.diagOn) "● " else "") + "网页诊断", has) { lifecycleScope.launch { diag = b.diagnose(this@MenuSheet) } }

            Head("Kavi")
            Item("问 Kavi 这一页", has) { BrowserChat.open = true }
            Item("带回聊天里问", has) { askKavi() }
            Item(if (b.teaching) "停止教学" else "教 Kavi 一个流程", has || b.teaching) {
                if (b.teaching) finishTeach() else {
                    b.startTeach()
                    b.notice = "开始记录：照常在网页上操作一遍，做完点底下的「做完了」。密码只记「这里要填密码」，不记内容。"
                }
            }
            Item("网页脚本", value = b.tab.scriptsRan.size.takeIf { it > 0 }?.let { "这页 $it 个在跑" } ?: b.scripts.size.takeIf { it > 0 }?.let { "$it 个" }) { showScripts = true }

            Head("收藏")
            Item("阅读列表", value = b.later.count { !it.read }.takeIf { it > 0 }?.let { "$it 篇没读" }) { showLater = true }
            Item("离线网页", value = b.offline.size.takeIf { it > 0 }?.let { "$it 个" }) { showOffline = true }

            Head("设置")
            Item("地址栏放在底部", switch = addressAtBottom) {
                addressAtBottom = !addressAtBottom
                getSharedPreferences("browser", android.content.Context.MODE_PRIVATE).edit().putBoolean("addressbottom", addressAtBottom).apply()
            }
            Item("所有网站都用电脑版", switch = b.desktopAll) { b.toggleDesktopAll() }
            Item("电脑版放大", value = "${b.desktopZoom}%") { zoomPick = true }
            Item("网页字号", value = "${b.textZoom}%") { textPick = true }
            Item("搜索引擎", value = mapOf("auto" to "自动", "google" to "Google", "bing" to "必应", "baidu" to "百度", "duckduckgo" to "DuckDuckGo")[b.searchEngine]) { searchPick = true }
            Item("去广告" + if (b.tab.blocked > 0) "（这页 ${b.tab.blocked} 个）" else "", switch = b.adBlock) { b.changeAdBlock(!b.adBlock) }
            Item("拦跟踪器" + if (b.tab.trackers > 0) "（这页 ${b.tab.trackers} 个）" else "", switch = b.trackBlock) { b.changeTrackBlock(!b.trackBlock) }
            if ((b.adBlock || b.trackBlock) && has) Item("这个网站不拦广告和跟踪器", switch = b.adAllowedHere()) { b.toggleAdAllow() }
            Item("只用加密连接（HTTPS）", switch = b.httpsOnly) {
                b.changeHttpsOnly(!b.httpsOnly)
                if (!b.httpsOnly) b.notice = "已关掉：不加密的网站也直接打开（同一个网络里的人能看到、改掉网页内容）。"
            }
            Item("自动填密码") { autofillInfo = true }

            Head("线路")
            listOf(BrowserNet.AUTO to "自动（国内直连，国外走代理）", BrowserNet.PROXY to "全部走代理", BrowserNet.DIRECT to "全部直连").forEach { (key, label) ->
                Item((if (b.route == key) "✓ " else "") + label) { if (b.route != key) { b.changeRoute(key); if (has) reload() } }
            }
            // 这两项属于设置里的「加强保护」，关着时不出来
            if (has && b.route == BrowserNet.AUTO && Net.config.hardened) Item("这个网站总走代理", switch = b.proxyForcedHere()) {
                lifecycleScope.launch { b.notice = b.toggleProxySite(this@MenuSheet) }
            }
            if (Net.config.hardened) Item("允许网页视频通话（会暴露真实 IP）", switch = b.webrtcAllowed) {
                b.changeWebrtc(!b.webrtcAllowed)
            }

            HorizontalDivider(Modifier.padding(top = 12.dp))
            Item("清除浏览数据", color = colors.error) { b.clearData(); editing = "" }
            Item("关闭浏览器") { finish() }
        }
    }
}

/** 正在朗读时底栏上面的一条：念到哪、语速、暂停 / 继续、停止。 */
@Composable
internal fun BrowserActivity.ReadingBar() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Speaker, null, Modifier.size(16.dp), tint = colors.onSecondaryContainer)
        Text((if (PageReader.paused) "已暂停：" else "正在朗读：") + PageReader.title.orEmpty() + "（${PageReader.index + 1}/${PageReader.total}）",
            Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { PageReader.nextSpeed() }) { Text(BrowserPages.rateText(PageReader.speed)) }
        TextButton(onClick = { PageReader.toggle() }) { Text(if (PageReader.paused) "继续" else "暂停") }
        TextButton(onClick = { PageReader.stop() }) { Text("停止") }
    }
}

/** 底栏小字：当前网页实际怎么上网。 */
internal fun BrowserActivity.routeLabel(): String {
    val route = JackBrowser.route
    val host = runCatching { Uri.parse(JackBrowser.address).host }.getOrNull()
    val proxied = when (route) {
        BrowserNet.DIRECT -> false
        BrowserNet.PROXY -> true
        else -> host != null && (Net.shouldProxy(host) || (Net.config.enabled && JackBrowser.proxyForcedHere()))
    }
    val mode = when (route) { BrowserNet.PROXY -> "🛡 全部走代理"; BrowserNet.DIRECT -> "全部直连"; else -> "自动" }
    val t = JackBrowser.tab
    val ads = listOfNotNull(t.blocked.takeIf { it > 0 }?.let { "$it 个广告" }, t.trackers.takeIf { it > 0 }?.let { "$it 个跟踪器" })
        .takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · 拦了 ").orEmpty() +
        (if (JackBrowser.address.startsWith("http://")) " · ⚠ 不加密" else "")
    return if (host == null) "线路：$mode" + if (route != BrowserNet.DIRECT && !Net.config.enabled) "（代理没开，现在都是直连）" else ""
    else "$mode · 这个网站" + (if (proxied) "走代理" else "直连（看得到你的 IP）") + ads
}

/** 把当前网页的内容带回聊天：新开一个对话，网页正文作为附件，用户接着问。 */
/** 演示教学做完：把记下的步骤交给浏览器里的 Kavi，让它整理成流程存下来。 */
internal fun BrowserActivity.finishTeach() {
    val b = JackBrowser
    if (BrowserChat.busy) { b.notice = "浏览器里的 Kavi 还在忙，等它做完再点「做完了」（记下的步骤还留着）。"; BrowserChat.open = true; return }
    val steps = b.stopTeach()
    if (steps.size < 2) { b.notice = "没记到操作，流程没存。"; return }
    BrowserChat.open = true
    BrowserChat.send(this, BrowserTeach.request(BrowserPages.site(steps.first().removePrefix("打开 ")).ifBlank { BrowserPages.site(b.address) }, steps))
}

/** 选要屏蔽的那块时底下的条：选中了什么、扩大一层、屏蔽、取消。 */
@Composable
internal fun BrowserActivity.PickBar() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp)) {
        val p = b.picked
        Text(if (p == null) "点一下网页上要屏蔽的地方（这时点了不会打开）" else "选中：${p.label}" + (if (p.count > 1) "，这一页一共 ${p.count} 处一样的" else ""),
            style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            TextButton(enabled = p != null, onClick = { b.pickWider() }) { Text("选大一点") }
            TextButton(enabled = p != null, onClick = { b.confirmPick() }) { Text("屏蔽") }
            TextButton(onClick = { b.stopPick() }) { Text("取消") }
        }
    }
}

/** 演示教学进行中：记了几步、做完了、不记了。 */
@Composable
internal fun BrowserActivity.TeachBar() {
    val b = JackBrowser
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.tertiaryContainer).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("正在记你的操作 · ${b.teachSteps.size} 步" + (b.teachSteps.lastOrNull()?.let { " · $it" } ?: ""), Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = colors.onTertiaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { finishTeach() }) { Text("做完了") }
        TextButton(onClick = { b.stopTeach(); b.notice = "不记了。" }) { Text("放弃") }
    }
}

/** 浏览器里的 Kavi：问这一页、让它在这一页上做事。从底部浮起的圆角半屏，要确认的事直接在这里给两个大按钮。 */
@Composable
internal fun BrowserActivity.ChatPanel() {
    val chat = BrowserChat
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(chat.lines.size, chat.lines.lastOrNull()?.text?.length, chat.asking) {
        val n = list.layoutInfo.totalItemsCount
        if (n > 0) list.scrollToItem(n - 1)
    }
    val h = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.45f).dp
    Surface(
        Modifier.fillMaxWidth().height(h),
        color = colors.surface,
        shape = Radii.sheetTop,
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 拖动条（点它收起）
            Box(Modifier.fillMaxWidth().clickable { BrowserChat.open = false }.padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.outlineVariant))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(36.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Kavi", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(if (chat.busy) (chat.progress ?: "想一想…") else "问这一页，或者让它在这页上做事",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chat.lines.isNotEmpty()) {
                    TextButton(onClick = { toChat(chat.transcript()) }) { Text("转到聊天") }
                    TextButton(onClick = { chat.clear() }) { Text("清空") }
                }
                IconButton(onClick = { BrowserChat.open = false }) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.lines.isEmpty()) item {
                    Text("问问这一页讲了什么，或者让 Kavi 在这页上做事：比价、填表、找东西、把操作存成流程。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                items(chat.lines.size) { i ->
                    val l = chat.lines[i]
                    if (l.role == "user") Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(l.text, Modifier.widthIn(max = 280.dp).clip(Radii.bubbleMine)
                            .background(colors.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    else Column(Modifier.fillMaxWidth()) {
                        if (l.steps.isNotEmpty()) Text(l.steps.joinToString(" → "), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                        if (l.text.isNotBlank()) Box(Modifier.clip(Radii.bubbleTheirs)
                            .background(colors.surfaceContainer).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            MarkdownBlocks(l.text, colors.onSurface, colors.surface, Modifier.fillMaxWidth(), textSizeSp = 15f)
                        }
                        l.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                        l.files.forEach { f ->
                            Text("打开「${f.name}」", Modifier.clickable { openFile(f) }.padding(vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.primary)
                        }
                    }
                }
                // 要用户拍板的事：就在对话里给两个大按钮
                chat.asking?.let { a ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text(a.body.take(600), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.material3.Button(onClick = { a.answer.complete(true) }, modifier = Modifier.weight(1f).height(48.dp),
                                    shape = Radii.m) { Text(a.action) }
                                androidx.compose.material3.FilledTonalButton(onClick = { a.answer.complete(false) }, modifier = Modifier.weight(1f).height(48.dp),
                                    shape = Radii.m) { Text("先不要") }
                            }
                        }
                    }
                }
            }
            // 输入：和聊天页一样的圆角胶囊，右边黑色圆按钮
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                .clip(Radii.xl).background(colors.surfaceContainer)
                .padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("问这一页，或者让它做事", color = colors.outline) },
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                val busy = chat.busy
                Surface(
                    onClick = { if (busy) chat.stop() else if (input.isNotBlank()) { chat.send(this@ChatPanel, input); input = "" } },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (busy || input.isNotBlank()) colors.onSurface else colors.surfaceVariant,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (busy) AppIcons.Stop else AppIcons.ArrowUp, if (busy) "停" else "发送", Modifier.size(if (busy) 14.dp else 18.dp),
                            tint = if (busy || input.isNotBlank()) colors.surface else colors.outline)
                    }
                }
            }
        }
    }
}

/** 用别的 App 打开浏览器里的 Kavi 存下的文件（表格、图片）。 */
internal fun BrowserActivity.openFile(f: java.io.File) {
    val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f) }.getOrNull()
        ?: run { JackBrowser.notice = "文件打不开：${f.name}"; return }
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, com.jack.ai.tools.ToolSession.mimeOf(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(i, f.name)) }.onFailure { JackBrowser.notice = "没有能打开「${f.name}」的应用" }
}

/** 把文字带回主聊天的输入框。 */
internal fun BrowserActivity.toChat(text: String) {
    startActivity(Intent(this, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(20_000))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/** 网页里选中一段字点「问 Kavi」：带着出处回到聊天，放进输入框，用户接着问。 */
internal fun BrowserActivity.askAboutSelection(sel: String) {
    val b = JackBrowser
    val text = "「" + sel.trim().take(4000) + "」\n——摘自「" + b.title.ifBlank { BrowserPages.host(b.address) } + "」" + b.address + "\n\n"
    startActivity(Intent(this, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/** 把网页正文（视频页带上字幕）存成文件带回聊天；prompt 给了就一起放进输入框。 */
internal fun BrowserActivity.askKavi(prompt: String? = null) {
    val b = JackBrowser
    lifecycleScope.launch {
        val text = runCatching { b.pageText(this@askKavi) }.getOrNull()
        if (text == null || text.substringAfter("\n\n").isBlank()) { b.notice = "读不到这个网页的文字（可能还没加载完，或者内容是图片）"; return@launch }
        val f = java.io.File(java.io.File(cacheDir, "exports").apply { mkdirs() }, BrowserPages.fileName(b.title, b.address))
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.writeText(text) } }
            .onFailure { b.notice = "没存下网页内容：${it.message}"; return@launch }
        val uri = androidx.core.content.FileProvider.getUriForFile(this@askKavi, "$packageName.files", f)
        startActivity(Intent(this@askKavi, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { if (prompt != null) putExtra(Intent.EXTRA_TEXT, prompt) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 回到已经开着的聊天页（不另开一个），浏览器的网页留着，下次打开还在
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/** 存成 PDF：用系统的打印，选「另存为 PDF」，存在哪由用户定。 */
internal fun BrowserActivity.takeLongShot() {
    if (!JackBrowser.userViewing || shooting) return
    if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
        shotPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
    }
    val w = JackBrowser.tab.web ?: return
    shooting = true
    shotJob = lifecycleScope.launch {
        try {
            val (saved, cut) = BrowserLongShot.capture(this@takeLongShot, w)
            longShot = saved
            JackBrowser.notice = if (cut) "页面太长，只截了前面一部分" else "长截图已存到相册 Pictures/Kavi"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            JackBrowser.notice = "页面图片太大，手机内存不够，没能完成截图"
        } catch (e: Exception) {
            JackBrowser.notice = "截图没完成：${e.message ?: "图片太大"}"
        } finally { shooting = false; shotJob = null }
    }
}

internal fun BrowserActivity.savePdf() {
    val b = JackBrowser
    val name = b.title.ifBlank { BrowserPages.host(b.address) }.ifBlank { "网页" }
    val pm = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
    runCatching { pm.print(name, b.webView(this).createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build()) }
        .onFailure { b.notice = "这台手机打不开系统的打印（存 PDF 要用它）：${it.message}" }
}

internal fun BrowserActivity.scanQr(img: String) {
    val b = JackBrowser
    lifecycleScope.launch {
        val text = runCatching { b.scanQr(img) }.getOrElse { b.notice = "图片没取下来：${it.message}"; return@launch }
        if (text.isNullOrBlank()) b.notice = "这张图里没识别出二维码。" else qr = text
    }
}

internal fun BrowserActivity.exitFullscreen() {
    JackBrowser.fullscreen?.second?.onCustomViewHidden()
    JackBrowser.fullscreen = null
}

internal fun BrowserActivity.closeFind() {
    finding = null
    JackBrowser.webView(this).clearMatches()
    JackBrowser.findCount = 0; JackBrowser.findIndex = 0
}

internal fun BrowserActivity.copy(text: String) =
    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网址", text))

internal fun BrowserActivity.share(url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, if (title.isBlank() || title == url) url else "$title\n$url")
        .putExtra(Intent.EXTRA_SUBJECT, title)
    runCatching { startActivity(Intent.createChooser(send, "分享网页")) }
}
