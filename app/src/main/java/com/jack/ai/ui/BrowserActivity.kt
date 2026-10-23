package com.jack.ai.ui

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
 * Jack 浏览器的完整页面：用户自己翻网页，或者看着 Jack 操作、随时接手。网页窗口和 Jack 用的是同一个（JackBrowser）。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var editing by mutableStateOf("")
    /** 识别出的二维码内容，等弹窗。 */
    private var qr by mutableStateOf<String?>(null)
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
        JackBrowser.setDark(settings.themeMode != ThemeMode.LIGHT)
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale) {
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
        intent.getStringExtra(EXTRA_URL)?.let { finding = null; open(it) }
    }

    override fun onResume() {
        super.onResume()
        JackBrowser.userViewing = true
    }

    override fun onPause() {
        super.onPause()
        JackBrowser.userViewing = false
        // 刚登录完 App 就被系统杀掉也不丢登录：离开浏览器时把 Cookie 写到磁盘
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (JackBrowser.address.isNotEmpty()) outState.putString(EXTRA_URL, JackBrowser.address)
    }

    override fun onDestroy() {
        JackBrowser.userViewing = false
        if (JackBrowser.chooser == ::chooseFiles) JackBrowser.chooser = null
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
                else -> { val w = b.webView(this); if (w.canGoBack()) { b.clearError(); w.goBack() } else finish() }
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
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除浏览数据", color = colors.error) }, onClick = { menu = false; b.clearData(); editing = "" })
                    }
                }
            }
            if (b.title.isNotBlank() && b.address.isNotEmpty()) {
                Text(b.title, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.loading < 100) LinearProgressIndicator(progress = { b.loading / 100f }, Modifier.fillMaxWidth().height(2.dp))
            finding?.let { FindBar(it) }
            b.error?.takeIf { !b.pageFailed }?.let { msg ->
                Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    TextButton(onClick = { b.clearError() }) { Text("知道了") }
                }
            }
            if (b.agentActive()) {
                Text("Jack 正在用这个浏览器。你可以直接接手操作，比如自己登录。",
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = b.canBack, onClick = { b.clearError(); b.webView(this@BrowserActivity).goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退") }
                IconButton(enabled = b.canForward, onClick = { b.clearError(); b.webView(this@BrowserActivity).goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进") }
                IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                IconButton(enabled = b.address.isNotEmpty(), onClick = { finding = null; b.home(); editing = "" }) { Icon(Icons.Default.Home, "起始页") }
                Text(
                    routeLabel(),
                    Modifier.weight(1f).padding(end = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 视频全屏：盖满整个屏幕
        full?.let { (view, _) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            }
        }
        }
        b.pressed?.let { p -> PressMenu(p) }
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
        // Jack 在操作时网页要下载的，等 Jack 用 download 确认；用户自己点的才在这里问
        b.pending?.takeIf { it.byUser }?.let { p ->
            val big = p.size > BrowserDownload.ASK_ABOVE
            AlertDialog(
                onDismissRequest = { b.pending = null },
                title = { Text("下载这个文件？") },
                text = {
                    Text("「${p.name}」" + (if (p.size > 0) "，${BrowserDownload.sizeText(p.size)}" else "（网站没说多大）") +
                        "\n存到手机的「下载/JackAI」文件夹。" +
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
                        }.onFailure { b.notice = "手机上没有能打开它的 App，可以在文件管理器的「下载/JackAI」里找到它。" }
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
            else -> host != null && Net.shouldProxy(host)
        }
        val mode = when (route) { BrowserNet.PROXY -> "全部走代理"; BrowserNet.DIRECT -> "全部直连"; else -> "自动" }
        return if (host == null) "线路：$mode" + if (route != BrowserNet.DIRECT && !Net.config.enabled) "（代理没开，现在都是直连）" else ""
        else "$mode · 这个网站" + if (proxied) "走代理" else "直连"
    }

    /** 把当前网页的内容带回聊天：新开一个对话，网页正文作为附件，用户接着问。 */
    private fun askKavi() {
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
                    Text("Jack 浏览器", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "开着 Jack 专用代理时，国外网站自动走代理，不用开系统 VPN，也不影响别的 App。" +
                            "Jack 也能用这个浏览器替你操作网页，你在这里登录过的网站它也能用。" +
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
