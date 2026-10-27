package com.jack.ai.ui

import androidx.compose.foundation.layout.size
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
import android.net.Uri
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

/**
 * Kavi 浏览器的完整页面：用户自己翻网页，或者看着 Kavi 操作、随时接手。网页窗口和 Kavi 用的是同一个（JackBrowser）。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var editing by mutableStateOf("")
    /** 标签页列表开着。 */
    private var showTabs by mutableStateOf(false)
    /** 识别出的二维码内容，等弹窗。 */
    private var qr by mutableStateOf<String?>(null)
    /** 管理这个网站屏蔽的东西。 */
    private var blockList by mutableStateOf(false)
    /** 自动填密码的说明。 */
    private var autofillInfo by mutableStateOf(false)
    /** 选开哪个分身。 */
    private var accountPick by mutableStateOf(false)
    /** 阅读模式：(标题, 正文 Markdown)。 */
    private var reader by mutableStateOf<Pair<String, String>?>(null)
    /** 网页诊断报告（null = 没打开）。 */
    private var diag by mutableStateOf<String?>(null)
    /** 页内查找：null 没在找，否则是要找的字。 */
    private var finding by mutableStateOf<String?>(null)

    /** 网页要选文件上传：等选完把结果交回网页（取消也要交一个 null，不然网页再点就没反应）。 */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val uris = if (r.resultCode != RESULT_OK || data == null) null
        else data.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun chooseFiles(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        val (type, more) = BrowserPages.pickTypes(params.acceptTypes)
        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).apply {
            if (more.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, more.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return runCatching { pickFiles.launch(Intent.createChooser(pick, "选文件")) }
            .onSuccess { fileCallback = cb }
            .isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 进程被杀后直接回到这里时，代理设置还没读：先读一遍（顺带告诉 Net 怎么分流）
        settings = Store(applicationContext).loadSettings()
        JackBrowser.userViewing = true
        JackBrowser.chooser = ::chooseFiles
        JackBrowser.askSelection = ::askAboutSelection
        JackBrowser.setDark(settings.themeMode != ThemeMode.LIGHT)
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
        // 被系统回收后回来：只记网址，先按线路设好代理再打开（直接恢复网页会在代理设好之前就直连出去）
        val url = savedInstanceState?.getString(EXTRA_URL) ?: intent?.getStringExtra(EXTRA_URL)
        if (url != null) open(url)
    }

    /** 浏览器已经开着时，从聊天里又点了一个链接：换成新网址。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 浏览器已经开着网页时，从聊天里点来的链接开在新标签页，原来的网页留着
        intent.getStringExtra(EXTRA_URL)?.let { url ->
            if (JackBrowser.address.isNotEmpty()) openInNewTab(url) else { finding = null; open(url) }
        }
    }

    override fun onResume() {
        super.onResume()
        JackBrowser.userViewing = true
    }

    override fun onPause() {
        super.onPause()
        JackBrowser.userViewing = false
        // 刚登录完 App 就被系统杀掉也不丢登录：离开浏览器时把 Cookie 写到磁盘
        JackBrowser.flushCookies()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (JackBrowser.address.isNotEmpty()) outState.putString(EXTRA_URL, JackBrowser.address)
    }

    override fun onDestroy() {
        JackBrowser.userViewing = false
        if (JackBrowser.chooser == ::chooseFiles) JackBrowser.chooser = null
        if (JackBrowser.askSelection == ::askAboutSelection) JackBrowser.askSelection = null
        fileCallback?.onReceiveValue(null); fileCallback = null
        JackBrowser.fullscreen?.second?.onCustomViewHidden(); JackBrowser.fullscreen = null
        JackBrowser.release(this)
        JackBrowser.clearProxyIfIdle()
        super.onDestroy()
    }

    private fun open(input: String) {
        val url = BrowserNet.normalize(input, proxiedNow())
        if (url.isEmpty()) return
        editing = url
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.EXTERNAL -> JackBrowser.external = url
            else -> lifecycleScope.launch { JackBrowser.load(this@BrowserActivity, url) }
        }
    }

    /** 新开一个标签页打开网址；开满了就在当前页打开。 */
    private fun openInNewTab(url: String?, incognito: Boolean = false, account: Int = 0) {
        val b = JackBrowser
        b.webView(this)
        finding = null
        if (b.tabs.size >= JackBrowser.MAX_TABS) b.notice = "最多开 ${JackBrowser.MAX_TABS} 个标签页，这次在当前页打开。可以点底栏的数字关掉几个。"
        else {
            b.newTab(incognito = incognito, account = account)
            if (account > 0) b.notice = "分身 $account：这里登录的账号和别的标签页分开。"
            if (incognito) b.notice = "隐身标签页：不带登录状态，不记最近访问；关掉最后一个隐身页，它的 Cookie 和网页数据就全清掉。"
        }
        editing = ""
        if (url != null) open(url)
    }

    private fun switchTab(i: Int) {
        if (finding != null) closeFind()
        JackBrowser.switchTo(i)
        showTabs = false
    }

    private fun reload() {
        val url = JackBrowser.address.ifEmpty { editing }
        if (url.isNotBlank()) open(url)
    }

    /** 现在打开新网页会不会走代理（决定搜索用 Google 还是必应）。 */
    private fun proxiedNow(): Boolean = JackBrowser.route != BrowserNet.DIRECT && Net.config.enabled

    private fun openExternal(url: String) {
        val intent = runCatching {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // 只许按「网页链接」的方式打开别的 App，不许网页点名启动某个内部页面
                component = null
                selector = null
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull()
        if (intent == null) { JackBrowser.error = "打不开这个链接"; return }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && BrowserNet.classify(fallback) == BrowserNet.Kind.OPEN) open(fallback)
            else JackBrowser.error = "手机上没有能打开它的 App"
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val colors = MaterialTheme.colorScheme
        val b = JackBrowser
        var menu by remember { mutableStateOf(false) }
        // 网页跳转后地址栏跟着变（用户正在改的时候除外：改完按前往才算）
        LaunchedEffect(b.address) { editing = b.address }
        // 视频全屏时藏起状态栏和导航栏
        val full = b.fullscreen
        LaunchedEffect(full != null) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (full != null) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }
        BackHandler {
            when {
                b.fullscreen != null -> exitFullscreen()
                finding != null -> closeFind()
                showTabs -> showTabs = false
                else -> {
                    val w = b.webView(this)
                    when {
                        w.canGoBack() -> { b.clearError(); w.goBack() }
                        // 退到这个标签页的头了：关掉它，回到别的标签页
                        b.tabs.size > 1 -> b.closeTab(b.current)
                        else -> finish()
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "关闭浏览器") }
                OutlinedTextField(
                    value = editing,
                    onValueChange = { editing = it },
                    placeholder = { Text("输入网址或要搜的内容") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open(editing) }),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val has = b.address.isNotEmpty()
                        DropdownMenuItem(text = { Text("问 Kavi") }, enabled = has, onClick = { menu = false; askKavi() })
                        DropdownMenuItem(text = { Text("存进资料库") }, enabled = has, onClick = {
                            menu = false
                            lifecycleScope.launch { b.notice = runCatching { b.saveToLibrary(this@BrowserActivity) }.getOrElse { "没存成：${it.message}" } }
                        })
                        DropdownMenuItem(text = { Text(if (b.teaching) "停止教学" else "教 Kavi 一个流程") }, enabled = has || b.teaching, onClick = {
                            menu = false
                            if (b.teaching) finishTeach() else {
                                b.startTeach()
                                b.notice = "开始记录：照常在网页上操作一遍，做完点底下的「做完了」。密码只记「这里要填密码」，不记内容。"
                            }
                        })
                        DropdownMenuItem(text = { Text("自动填密码") }, onClick = { menu = false; autofillInfo = true })
                        DropdownMenuItem(text = { Text("屏蔽网页上的东西" + b.blocksHere().size.takeIf { it > 0 }?.let { "（$it）" }.orEmpty()) }, enabled = has, onClick = {
                            menu = false
                            if (b.blocksHere().isNotEmpty()) blockList = true else b.startPick()
                        })
                        DropdownMenuItem(text = { Text("阅读模式") }, enabled = has, onClick = {
                            menu = false
                            lifecycleScope.launch { b.readable(this@BrowserActivity)?.let { reader = it } ?: run { b.notice = "这一页找不出正文（可能不是文章页，或者还没加载完）" } }
                        })
                        DropdownMenuItem(text = { Text(if (b.tab.translated) "显示原文" else "翻译成中文") }, enabled = has && b.tab.translating == null, onClick = {
                            menu = false
                            if (b.tab.translated) b.showOriginal() else lifecycleScope.launch { b.translate(this@BrowserActivity)?.let { b.notice = it } }
                        })
                        DropdownMenuItem(text = { Text((if (b.address.isNotEmpty() && BrowserPages.isDesktop(b.desktopSites, b.address)) "✓ " else "") + "电脑版") },
                            enabled = has, onClick = { menu = false; b.toggleDesktop() })
                        DropdownMenuItem(text = { Text("分享") }, enabled = has, onClick = { menu = false; share(b.address, b.title) })
                        DropdownMenuItem(text = { Text("存成 PDF") }, enabled = has, onClick = { menu = false; savePdf() })
                        DropdownMenuItem(text = { Text("页内查找") }, enabled = has, onClick = { menu = false; finding = "" })
                        DropdownMenuItem(text = { Text(if (b.marks.any { it.url == b.address }) "移出书签" else "加入书签") }, enabled = has, onClick = {
                            menu = false
                            val adding = b.marks.none { it.url == b.address }
                            b.toggleMark()
                            b.notice = if (adding) "已加入书签，起始页能看到" else "已移出书签"
                        })
                        DropdownMenuItem(text = { Text("复制链接") }, enabled = has, onClick = {
                            menu = false
                            copy(b.address)
                            b.notice = "已复制链接"
                        })
                        DropdownMenuItem(text = { Text("用系统浏览器打开") }, enabled = has, onClick = {
                            menu = false
                            Links.system(this@BrowserActivity, b.address)
                        })
                        DropdownMenuItem(text = { Text((if (b.tab.diagOn) "● " else "") + "网页诊断") }, enabled = has, onClick = {
                            menu = false
                            lifecycleScope.launch { diag = b.diagnose(this@BrowserActivity) }
                        })
                        HorizontalDivider()
                        Text("线路", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        listOf(
                            BrowserNet.AUTO to "自动（国内直连，国外走代理）",
                            BrowserNet.PROXY to "全部走代理",
                            BrowserNet.DIRECT to "全部直连",
                        ).forEach { (key, label) ->
                            DropdownMenuItem(text = { Text((if (b.route == key) "✓ " else "    ") + label) }, onClick = {
                                menu = false
                                if (b.route != key) {
                                    b.changeRoute(key)
                                    if (b.address.isNotEmpty()) reload()
                                }
                            })
                        }
                        // 这两项属于设置里的「加强保护」，关着时不出来
                        if (has && b.route == BrowserNet.AUTO && Net.config.hardened) DropdownMenuItem(text = { Text((if (b.proxyForcedHere()) "✓ " else "    ") + "这个网站总走代理") }, onClick = {
                            menu = false
                            lifecycleScope.launch { b.notice = b.toggleProxySite(this@BrowserActivity) }
                        })
                        if (Net.config.hardened) DropdownMenuItem(text = { Text((if (b.webrtcAllowed) "✓ " else "    ") + "允许网页视频通话（会暴露真实 IP）") }, onClick = {
                            menu = false
                            b.changeWebrtc(!b.webrtcAllowed)
                            b.notice = if (b.webrtcAllowed) "已允许 WebRTC：网页能视频通话，但也能查到你的真实 IP" else "已关掉 WebRTC 查 IP：网页查不到你的真实 IP，视频通话可能用不了"
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text((if (b.adBlock) "✓ " else "    ") + "去广告") }, onClick = { menu = false; b.changeAdBlock(!b.adBlock) })
                        if (b.adBlock && has) DropdownMenuItem(text = { Text((if (b.adAllowedHere()) "✓ " else "    ") + "这个网站不去广告") },
                            onClick = { menu = false; b.toggleAdAllow() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除浏览数据", color = colors.error) }, onClick = { menu = false; b.clearData(); editing = "" })
                    }
                }
            }
            if (b.tab.badge.isNotEmpty() || (b.title.isNotBlank() && b.address.isNotEmpty())) {
                Text(b.tab.badge + b.title, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.loading < 100) LinearProgressIndicator(progress = { b.loading / 100f }, Modifier.fillMaxWidth().height(2.dp))
            finding?.let { FindBar(it) }
            b.tab.translating?.let { msg ->
                Text(msg, Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
            }
            b.error?.takeIf { !b.pageFailed }?.let { msg ->
                Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    TextButton(onClick = { b.clearError() }) { Text("知道了") }
                }
            }
            if (b.agentActive()) {
                Text("Kavi 正在用这个浏览器。你可以直接接手操作，比如自己登录。",
                    Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(b.hostGen) {
                    AndroidView(factory = { c ->
                        // 下拉刷新：要多拉一段才触发，免得在网页里往上翻时误刷新
                        SwipeRefreshLayout(c).apply {
                            addView(b.claim(this@BrowserActivity))
                            setDistanceToTriggerSync((160 * resources.displayMetrics.density).toInt())
                            setOnRefreshListener { isRefreshing = false; reload() }
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
                if (b.address.isEmpty() && b.loading >= 100 && !b.pageFailed) StartPage()
                b.error?.takeIf { b.pageFailed }?.let { ErrorPage(it) }
            }
            b.downloading?.let { msg ->
                Text(msg, Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.picking) PickBar()
            if (b.teaching) TeachBar()
            if (BrowserChat.open) ChatPanel()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = b.canBack, onClick = { b.clearError(); b.webView(this@BrowserActivity).goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退") }
                IconButton(enabled = b.canForward, onClick = { b.clearError(); b.webView(this@BrowserActivity).goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进") }
                IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                IconButton(enabled = b.address.isNotEmpty(), onClick = { finding = null; b.home(); editing = "" }) { Icon(Icons.Default.Home, "起始页") }
                IconButton(onClick = { showTabs = true }) {
                    Text(
                        b.tabs.size.coerceAtLeast(1).toString(),
                        Modifier.border(1.5.dp, colors.onSurface, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(onClick = { BrowserChat.open = !BrowserChat.open }) {
                    Icon(Icons.Default.Face, "问 Kavi 这一页", tint = if (BrowserChat.open || BrowserChat.busy) colors.primary else colors.onSurface)
                }
                // 点一下在「自动」和「全部走代理」之间切换（全部直连在菜单里）
                Text(
                    routeLabel(),
                    Modifier.weight(1f).padding(end = 12.dp).clickable {
                        val next = if (b.route == BrowserNet.PROXY) BrowserNet.AUTO else BrowserNet.PROXY
                        b.changeRoute(next)
                        b.notice = if (next == BrowserNet.PROXY) "全部走代理：网站都看不到你的真实 IP（国内网站可能慢一点）" else "自动：国内直连，国外走代理"
                        if (b.address.isNotEmpty()) reload()
                    }.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (proxiedNow() && (b.route == BrowserNet.PROXY || b.proxyForcedHere())) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showTabs) Box(Modifier.fillMaxSize().safeDrawingPadding()) { TabsPanel() }
        // 视频全屏：盖满整个屏幕
        full?.let { (view, _) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            }
        }
        }
        b.pressed?.let { p -> PressMenu(p) }
        // 面板开着时在面板里问；收着时弹窗问
        BrowserChat.asking?.takeIf { !BrowserChat.open }?.let { a ->
            AlertDialog(
                onDismissRequest = { a.answer.complete(false) },
                title = { Text(a.title) },
                text = { Text(a.body.take(1500), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = { a.answer.complete(true) }) { Text(a.action) } },
                dismissButton = { TextButton(onClick = { a.answer.complete(false) }) { Text("不要") } },
            )
        }
        if (blockList) {
            val here = b.blocksHere()
            AlertDialog(
                onDismissRequest = { blockList = false },
                title = { Text("这个网站屏蔽的东西") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        if (here.isEmpty()) Text("没有了。", style = MaterialTheme.typography.bodyMedium)
                        here.forEach { sel ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { b.unblock(sel) }) { Text("恢复") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { blockList = false; b.startPick() }) { Text("再屏蔽一块") } },
                dismissButton = { TextButton(onClick = { blockList = false }) { Text("关闭") } },
            )
        }
        if (autofillInfo) {
            val on = remember { runCatching { getSystemService(android.view.autofill.AutofillManager::class.java)?.isEnabled == true }.getOrDefault(false) }
            AlertDialog(
                onDismissRequest = { autofillInfo = false },
                title = { Text("自动填密码") },
                text = {
                    Text(
                        (if (on) "手机的自动填充已经开着。在网页的账号、密码框上点一下，键盘上方或框下面会出现保存过的账号，点它就填好了；第一次登录后系统会问要不要存密码。"
                        else "手机还没开自动填充。到 设置 里搜「自动填充」（常在「密码与安全」「密码保险箱」里），选一个密码管理器（手机自带的或 Google 密码管理工具）打开。") +
                            "\n\n密码由你手机的密码管理器保存和填写，Kavi 看不到、也不替你填。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    if (!on) TextButton(onClick = {
                        autofillInfo = false
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    }) { Text("去设置") }
                },
                dismissButton = { TextButton(onClick = { autofillInfo = false }) { Text("知道了") } },
            )
        }
        if (accountPick) {
            AlertDialog(
                onDismissRequest = { accountPick = false },
                title = { Text("开一个分身") },
                text = {
                    Column {
                        Text("每个分身的登录状态各管各的：同一个网站能在不同分身里登不同的账号。关掉标签页登录还在。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        (1..JackBrowser.ACCOUNTS).forEach { n ->
                            Text("分身 $n" + b.tabs.count { it.account == n }.takeIf { it > 0 }?.let { "（开着 $it 个）" }.orEmpty(),
                                Modifier.fillMaxWidth().clickable { accountPick = false; showTabs = false; openInNewTab(null, account = n) }.padding(vertical = 12.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { accountPick = false }) { Text("取消") } },
            )
        }
        reader?.let { (title, md) ->
            TextViewer(
                name = title.ifBlank { "正文" } + ".md",
                load = { md to false },
                onDismiss = { reader = null },
                subtitle = BrowserPages.host(b.address),
                onShare = { share(md, title) },
                extra = listOf("让 Kavi 总结" to { reader = null; askKavi("总结一下这篇") }),
            )
        }
        diag?.let { report ->
            AlertDialog(
                onDismissRequest = { diag = null },
                title = { Text("网页诊断") },
                text = {
                    Column {
                        Text(
                            if (b.tab.diagOn) "正在记录。照常操作出问题的地方（比如点登录），再回来点「刷新」看，点「复制」发给开发者。"
                            else "网页点了没反应、打不开时用：点「开始记录」（网页会刷新），照常操作一遍出问题的地方，再打开这里点「复制」发给开发者。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer(
                            Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                        ) {
                            Text(report, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (b.tab.diagOn) {
                            TextButton(onClick = { lifecycleScope.launch { diag = b.diagnose(this@BrowserActivity) } }) { Text("刷新") }
                            TextButton(onClick = { b.stopDiag(); diag = null }) { Text("停止记录") }
                        } else TextButton(onClick = { b.startDiag(); diag = null }) { Text("开始记录") }
                        TextButton(onClick = {
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网页诊断", report))
                            b.notice = "已复制诊断报告"
                        }) { Text("复制") }
                    }
                },
                dismissButton = { TextButton(onClick = { diag = null }) { Text("关闭") } },
            )
        }
        qr?.let { text ->
            AlertDialog(
                onDismissRequest = { qr = null },
                title = { Text("二维码") },
                text = { Text(text.take(500), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Row {
                        TextButton(onClick = { qr = null; copy(text); b.notice = "已复制" }) { Text("复制") }
                        if (BrowserPages.isWeb(text)) TextButton(onClick = { qr = null; open(text) }) { Text("打开") }
                        else if (BrowserNet.classify(text) == BrowserNet.Kind.EXTERNAL) TextButton(onClick = { qr = null; b.external = text }) { Text("打开") }
                    }
                },
                dismissButton = { TextButton(onClick = { qr = null }) { Text("关闭") } },
            )
        }
        b.external?.let { url ->
            AlertDialog(
                onDismissRequest = { b.external = null },
                title = { Text("打开别的 App？") },
                text = { Text("网页想用别的 App 打开：\n${url.take(200)}") },
                confirmButton = { TextButton(onClick = { b.external = null; openExternal(url) }) { Text("打开") } },
                dismissButton = { TextButton(onClick = { b.external = null }) { Text("不了") } },
            )
        }
        // Kavi 在操作时网页要下载的，等 Kavi 用 download 确认；用户自己点的才在这里问
        b.pending?.takeIf { it.byUser }?.let { p ->
            val big = p.size > BrowserDownload.ASK_ABOVE
            AlertDialog(
                onDismissRequest = { b.pending = null },
                title = { Text("下载这个文件？") },
                text = {
                    Text("「${p.name}」" + (if (p.size > 0) "，${BrowserDownload.sizeText(p.size)}" else "（网站没说多大）") +
                        "\n存到手机的「下载/Kavi」文件夹。" +
                        (if (big) "\n文件比较大，会用掉相应的代理流量。" else "") +
                        (if (p.name.endsWith(".apk", true)) "\n这是安卓安装包，只装信得过的来源。" else ""))
                },
                confirmButton = { TextButton(onClick = { b.saveToPhone(this@BrowserActivity, p) }) { Text("下载") } },
                dismissButton = { TextButton(onClick = { b.pending = null }) { Text("取消") } },
            )
        }
        b.saved?.let { f ->
            AlertDialog(
                onDismissRequest = { b.saved = null },
                title = { Text("下载好了") },
                text = { Text("「${f.name}」已存到${f.where}。") },
                confirmButton = {
                    TextButton(onClick = {
                        b.saved = null
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { b.notice = "手机上没有能打开它的 App，可以在文件管理器的「下载/Kavi」里找到它。" }
                    }) { Text("打开") }
                },
                dismissButton = { TextButton(onClick = { b.saved = null }) { Text("好") } },
            )
        }
        b.notice?.let { msg ->
            AlertDialog(
                onDismissRequest = { b.notice = null },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { b.notice = null }) { Text("好") } },
            )
        }
    }

    /** 底栏小字：当前网页实际怎么上网。 */
    private fun routeLabel(): String {
        val route = JackBrowser.route
        val host = runCatching { Uri.parse(JackBrowser.address).host }.getOrNull()
        val proxied = when (route) {
            BrowserNet.DIRECT -> false
            BrowserNet.PROXY -> true
            else -> host != null && (Net.shouldProxy(host) || (Net.config.enabled && JackBrowser.proxyForcedHere()))
        }
        val mode = when (route) { BrowserNet.PROXY -> "🛡 全部走代理"; BrowserNet.DIRECT -> "全部直连"; else -> "自动" }
        val ads = JackBrowser.tab.blocked.takeIf { it > 0 }?.let { " · 拦了 $it 个广告" }.orEmpty()
        return if (host == null) "线路：$mode" + if (route != BrowserNet.DIRECT && !Net.config.enabled) "（代理没开，现在都是直连）" else ""
        else "$mode · 这个网站" + (if (proxied) "走代理" else "直连（看得到你的 IP）") + ads
    }

    /** 把当前网页的内容带回聊天：新开一个对话，网页正文作为附件，用户接着问。 */
    /** 演示教学做完：把记下的步骤交给浏览器里的 Kavi，让它整理成流程存下来。 */
    private fun finishTeach() {
        val b = JackBrowser
        if (BrowserChat.busy) { b.notice = "浏览器里的 Kavi 还在忙，等它做完再点「做完了」（记下的步骤还留着）。"; BrowserChat.open = true; return }
        val steps = b.stopTeach()
        if (steps.size < 2) { b.notice = "没记到操作，流程没存。"; return }
        BrowserChat.open = true
        BrowserChat.send(this, BrowserTeach.request(BrowserPages.site(steps.first().removePrefix("打开 ")).ifBlank { BrowserPages.site(b.address) }, steps))
    }

    /** 选要屏蔽的那块时底下的条：选中了什么、扩大一层、屏蔽、取消。 */
    @Composable
    private fun PickBar() {
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
    private fun TeachBar() {
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
    private fun ChatPanel() {
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
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
                            Text(l.text, Modifier.widthIn(max = 280.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                                .background(colors.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        else Column(Modifier.fillMaxWidth()) {
                            if (l.steps.isNotEmpty()) Text(l.steps.joinToString(" → "), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                            if (l.text.isNotBlank()) Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp))
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
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text(a.action) }
                                    androidx.compose.material3.FilledTonalButton(onClick = { a.answer.complete(false) }, modifier = Modifier.weight(1f).height(48.dp),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text("先不要") }
                                }
                            }
                        }
                    }
                }
                // 输入：和聊天页一样的圆角胶囊，右边黑色圆按钮
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(colors.surfaceContainer)
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
                        onClick = { if (busy) chat.stop() else if (input.isNotBlank()) { chat.send(this@BrowserActivity, input); input = "" } },
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
    private fun openFile(f: java.io.File) {
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f) }.getOrNull()
            ?: run { JackBrowser.notice = "文件打不开：${f.name}"; return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, com.jack.ai.tools.ToolSession.mimeOf(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, f.name)) }.onFailure { JackBrowser.notice = "没有能打开「${f.name}」的应用" }
    }

    /** 把文字带回主聊天的输入框。 */
    private fun toChat(text: String) {
        startActivity(Intent(this, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(20_000))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** 网页里选中一段字点「问 Kavi」：带着出处回到聊天，放进输入框，用户接着问。 */
    private fun askAboutSelection(sel: String) {
        val b = JackBrowser
        val text = "「" + sel.trim().take(4000) + "」\n——摘自「" + b.title.ifBlank { BrowserPages.host(b.address) } + "」" + b.address + "\n\n"
        startActivity(Intent(this, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** 把网页正文（视频页带上字幕）存成文件带回聊天；prompt 给了就一起放进输入框。 */
    private fun askKavi(prompt: String? = null) {
        val b = JackBrowser
        lifecycleScope.launch {
            val text = runCatching { b.pageText(this@BrowserActivity) }.getOrNull()
            if (text == null || text.substringAfter("\n\n").isBlank()) { b.notice = "读不到这个网页的文字（可能还没加载完，或者内容是图片）"; return@launch }
            val f = java.io.File(java.io.File(cacheDir, "exports").apply { mkdirs() }, BrowserPages.fileName(b.title, b.address))
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.writeText(text) } }
                .onFailure { b.notice = "没存下网页内容：${it.message}"; return@launch }
            val uri = androidx.core.content.FileProvider.getUriForFile(this@BrowserActivity, "$packageName.files", f)
            startActivity(Intent(this@BrowserActivity, com.jack.ai.MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { if (prompt != null) putExtra(Intent.EXTRA_TEXT, prompt) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 回到已经开着的聊天页（不另开一个），浏览器的网页留着，下次打开还在
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** 存成 PDF：用系统的打印，选「另存为 PDF」，存在哪由用户定。 */
    private fun savePdf() {
        val b = JackBrowser
        val name = b.title.ifBlank { BrowserPages.host(b.address) }.ifBlank { "网页" }
        val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
        runCatching { pm.print(name, b.webView(this).createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build()) }
            .onFailure { b.notice = "这台手机打不开系统的打印（存 PDF 要用它）：${it.message}" }
    }

    private fun scanQr(img: String) {
        val b = JackBrowser
        lifecycleScope.launch {
            val text = runCatching { b.scanQr(img) }.getOrElse { b.notice = "图片没取下来：${it.message}"; return@launch }
            if (text.isNullOrBlank()) b.notice = "这张图里没识别出二维码。" else qr = text
        }
    }

    private fun exitFullscreen() {
        JackBrowser.fullscreen?.second?.onCustomViewHidden()
        JackBrowser.fullscreen = null
    }

    private fun closeFind() {
        finding = null
        JackBrowser.webView(this).clearMatches()
        JackBrowser.findCount = 0; JackBrowser.findIndex = 0
    }

    private fun copy(text: String) =
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网址", text))

    private fun share(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, if (title.isBlank() || title == url) url else "$title\n$url")
            .putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { startActivity(Intent.createChooser(send, "分享网页")) }
    }

    /** 页内查找：边打字边找，上下跳，显示第几处 / 共几处。 */
    @Composable
    private fun FindBar(q: String) {
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
    @Composable
    private fun TabsPanel() {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showTabs = false }) { Icon(Icons.Default.Close, "收起") }
                    Text("标签页（${b.tabs.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (b.incognitoSupported()) {
                        TextButton(enabled = b.tabs.size < JackBrowser.MAX_TABS, onClick = { accountPick = true }) { Text("分身") }
                        TextButton(enabled = b.tabs.size < JackBrowser.MAX_TABS, onClick = { showTabs = false; openInNewTab(null, incognito = true) }) { Text("隐身") }
                    }
                    TextButton(enabled = b.tabs.size < JackBrowser.MAX_TABS, onClick = { showTabs = false; openInNewTab(null) }) { Text("新标签页") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(b.tabs.size, key = { b.tabs[it].id }) { i ->
                        val t = b.tabs[i]
                        val on = i == b.current
                        Row(
                            Modifier.fillMaxWidth().background(if (on) colors.primaryContainer else Color.Transparent)
                                .clickable { switchTab(i) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.badge + t.title.ifBlank { if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address) },
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (on) colors.onPrimaryContainer else colors.onSurface)
                                if (t.address.isNotEmpty()) Text(BrowserPages.host(t.address), style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { b.closeTab(i); if (b.tabs.isEmpty()) showTabs = false }) { Icon(Icons.Default.Close, "关闭这个标签页") }
                        }
                    }
                }
            }
        }
    }

    /** 网页没打开：盖一层干净的说明，不露出网页组件自带的英文出错页。 */
    @Composable
    private fun ErrorPage(msg: String) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("网页没打开", style = MaterialTheme.typography.titleMedium)
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reload() }) { Text("重试") }
                val url = JackBrowser.address.ifEmpty { editing }
                if (BrowserPages.isWeb(url)) OutlinedButton(onClick = { Links.system(this@BrowserActivity, url) }) { Text("用系统浏览器打开") }
            }
        }
    }

    /** 长按链接或图片弹出的菜单。 */
    @Composable
    private fun PressMenu(p: JackBrowser.Pressed) {
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
                        item("打开") { open(link) }
                        item("在新标签页打开") { openInNewTab(link) }
                        item("复制链接") { copy(link); b.notice = "已复制链接" }
                        item("分享链接") { share(link, "") }
                        item("用系统浏览器打开") { Links.system(this@BrowserActivity, link) }
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

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun StartPage() {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        LazyColumn(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Kavi 浏览器", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "开着 Kavi 专用代理时，国外网站自动走代理，不用开系统 VPN，也不影响别的 App。" +
                            "Kavi 也能用这个浏览器替你操作网页，你在这里登录过的网站它也能用。" +
                            "这里的登录状态和手机浏览器不共用。不能用来登录 Google 账号（Google 不允许）。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Google" to "https://www.google.com", "维基百科" to "https://zh.wikipedia.org", "GitHub" to "https://github.com",
                            "YouTube" to "https://m.youtube.com", "Notion" to "https://www.notion.so").forEach { (name, url) ->
                            OutlinedButton(onClick = { open(url) }) { Text(name) }
                        }
                    }
                }
            }
            if (b.marks.isNotEmpty()) {
                item { Section("书签") }
                items(b.marks, key = { "m" + it.url }) { PageRow(it) }
            }
            if (b.visits.isNotEmpty()) {
                item { Section("最近访问") }
                items(b.visits.take(15), key = { "v" + it.url }) { PageRow(it) }
            }
        }
    }

    @Composable
    private fun Section(name: String) =
        Text(name, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

    @Composable
    private fun PageRow(v: BrowserPages.Visit) {
        Column(Modifier.fillMaxWidth().clickable { open(v.url) }.padding(vertical = 8.dp)) {
            Text(BrowserPages.label(v), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(BrowserPages.host(v.url), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
