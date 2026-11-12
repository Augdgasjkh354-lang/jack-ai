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

/**
 * Kavi 浏览器的完整页面：用户自己翻网页，或者看着 Kavi 操作、随时接手。网页窗口和 Kavi 用的是同一个（JackBrowser）。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
class BrowserActivity : androidx.fragment.app.FragmentActivity() {
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
    /** 选网页字号。 */
    private var textPick by mutableStateOf(false)
    /** 历史记录页开着。 */
    private var shotJob: kotlinx.coroutines.Job? = null
    private var shooting by mutableStateOf(false)
    private var longShot by mutableStateOf<com.jack.ai.data.Downloads.Saved?>(null)
    private val shotPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takeLongShot() else JackBrowser.notice = "没有相册存储权限，无法保存长截图"
    }
    private var showDownloads by mutableStateOf(false)
    private var showLater by mutableStateOf(false)
    private var searchPick by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    /** 选电脑版放大多少。 */
    private var zoomPick by mutableStateOf(false)
    /** 选开哪个分身。 */
    private var accountPick by mutableStateOf(false)
    /** 阅读模式：(标题, 正文 Markdown)。 */
    private var reader by mutableStateOf<Pair<String, String>?>(null)
    /** 选视频倍速。 */
    private var ratePick by mutableStateOf(false)
    /** 菜单面板开着；地址栏正在打字。 */
    private var menu by mutableStateOf(false)
    private var typing by mutableStateOf(false)
    /** 地址栏放在底部（单手够得着）还是顶上。 */
    private var addressAtBottom by mutableStateOf(true)
    private val addressFocus = androidx.compose.ui.focus.FocusRequester()
    /** 网页脚本列表开着；正在改的脚本（新建的 id 是新的）。 */
    private var showScripts by mutableStateOf(false)
    private var editScript by mutableStateOf<com.jack.ai.api.UserScripts.Script?>(null)
    /** 标签页列表只看哪个分组（null = 全部）；长按的标签页（换分组）；分组的菜单（"" = 全部）；新建 / 改名分组。 */
    private var tabGroup by mutableStateOf<String?>(null)
    private var tabMove by mutableStateOf<JackBrowser.Tab?>(null)
    private var groupMenu by mutableStateOf<String?>(null)
    private var groupName by mutableStateOf<Pair<String, JackBrowser.Tab?>?>(null)
    /** 书签页、离线网页列表开着；正在看的离线网页。 */
    private var showMarks by mutableStateOf(false)
    private var showOffline by mutableStateOf(false)
    private var offlineView by mutableStateOf<BrowserPages.OfflinePage?>(null)
    /** 这个网站的数据（null = 没打开）。 */
    private var siteData by mutableStateOf<JackBrowser.SiteData?>(null)

    /** 导入别的浏览器导出的 bookmarks.html。 */
    private val importMarks = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { String(it.readNBytesCompat(20 shl 20), Charsets.UTF_8) } }.getOrNull()
            }
            if (html == null) { JackBrowser.notice = "文件没读出来"; return@launch }
            val (found, added) = JackBrowser.importMarks(html)
            JackBrowser.notice = when {
                found == 0 -> "这个文件里没找到书签。要用浏览器「导出书签」得到的 .html 文件。"
                added == 0 -> "文件里的 $found 个书签都已经有了。"
                else -> "导入了 $added 个书签" + (if (found > added) "（另外 ${found - added} 个已经有了）" else "") + "。"
            }
        }
    }
    /** 导出书签成 bookmarks.html，别的浏览器能导入。 */
    private val exportMarks = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri ?: return@registerForActivityResult
        val html = com.jack.ai.api.BrowserBookmarks.export(JackBrowser.marks)
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) } != null }.getOrDefault(false)
            }
            JackBrowser.notice = if (ok) "已导出 ${JackBrowser.marks.size} 个书签。" else "没导出成"
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    /** 添加到主屏幕：(网址, 图标名字)，等用户改名字。 */
    private var shortcutAsk by mutableStateOf<Pair<String, String>?>(null)

    /** 地址栏的语音输入：说完直接打开（网址就去，别的就搜）。 */
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { open(it) }
    }
    /** 地址栏的扫一扫：网址直接打开，别的弹出来能复制。 */
    private val scan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val text = r.contents?.trim()?.takeIf { it.isNotEmpty() } ?: return@registerForActivityResult
        if (BrowserPages.isWeb(text)) open(text) else qr = text
    }

    private fun startVoice() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说要搜的内容或网址")
        runCatching { voice.launch(i) }.onFailure { JackBrowser.notice = "手机没有语音识别服务，可以用输入法自带的语音输入" }
    }

    private fun startScan() {
        runCatching {
            scan.launch(com.journeyapps.barcodescanner.ScanOptions().setPrompt("扫网址二维码").setBeepEnabled(false)
                .setOrientationLocked(false).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE))
        }.onFailure { JackBrowser.notice = "打不开扫码：${it.message}" }
    }

    /** 朗读这页：取正文念（隐身页通知栏上不显示标题）。 */
    private fun readAloud() {
        val b = JackBrowser
        lifecycleScope.launch {
            val page = b.readable(this@BrowserActivity)
            if (page == null) { b.notice = "这一页找不出能念的正文（可能不是文章页，或者还没加载完）"; return@launch }
            val title = if (b.tab.incognito) "隐身标签页" else page.first.ifBlank { b.title }
            PageReader.start(this@BrowserActivity, title, page.second)
        }
    }
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

    /** 网页要的手机权限（相机、麦克风、位置）：用户在弹窗里允许后，再向系统要。 */
    private val askSystem = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (kinds, done) = permWaiting ?: return@registerForActivityResult
        permWaiting = null
        val ok = kinds.all { k -> systemNames(k).any { has(it) } }
        if (!ok) JackBrowser.notice = "手机没给 Kavi ${BrowserPages.kindsText(kinds)}权限，网页用不了。可以到系统设置 → 应用 → Kavi → 权限里打开。"
        done(ok)
    }
    private var permWaiting: Pair<Set<String>, (Boolean) -> Unit>? = null

    private fun systemNames(kind: String) = when (kind) {
        BrowserPages.CAMERA -> listOf(Manifest.permission.CAMERA)
        BrowserPages.MIC -> listOf(Manifest.permission.RECORD_AUDIO)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun systemPerms(kinds: Set<String>, done: (Boolean) -> Unit) {
        if (kinds.all { k -> systemNames(k).any { has(it) } }) { done(true); return }
        permWaiting?.second?.invoke(false)
        permWaiting = kinds to done
        runCatching { askSystem.launch(kinds.flatMap { systemNames(it) }.toTypedArray()) }
            .onFailure { permWaiting = null; done(false) }
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
        JackBrowser.systemPerms = ::systemPerms
        JackBrowser.askSelection = ::askAboutSelection
        JackBrowser.setDark(settings.themeMode != ThemeMode.LIGHT)
        addressAtBottom = getSharedPreferences("browser", MODE_PRIVATE).getBoolean("addressbottom", true)
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen()
                    // 上着锁：盖住网页，点了再解锁
                    if (locked) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable { unlock() },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Kavi 已上锁", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { unlock() }) { Text("解锁") }
                    }
                }
            }
        }
        // App 重开后第一次进来：把上次的标签页建回来（当前页由 Screen 里打开，别的切过去时再加载）
        val restored = JackBrowser.restoreTabs(this)
        JackBrowser.warmHistory()
        val linked = intent?.getStringExtra(EXTRA_URL).takeIf { savedInstanceState == null }
        if (restored) { if (linked != null) openInNewTab(linked) }
        else {
            // 被系统回收后回来：只记网址，先按线路设好代理再打开（直接恢复网页会在代理设好之前就直连出去）
            val url = savedInstanceState?.getString(EXTRA_URL) ?: intent?.getStringExtra(EXTRA_URL)
            if (url != null) open(url)
        }
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

    // ---------- 应用锁：从桌面的浏览器图标直接进来也要解锁（和聊天页共用一个锁） ----------
    private var locked by mutableStateOf(false)
    private var prompting = false

    override fun onStart() {
        super.onStart()
        val lock = runCatching { Store(applicationContext).loadSettings().appLock }.getOrDefault(false)
        val last = com.jack.ai.MainActivity.lastActive
        if (lock && (last == 0L || android.os.SystemClock.elapsedRealtime() - last > 30_000)) locked = true
    }

    private fun unlock() {
        if (prompting) return
        val bm = androidx.biometric.BiometricManager.from(this)
        val kinds = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (bm.canAuthenticate(kinds) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) { locked = false; return }
        prompting = true
        androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                prompting = false; locked = false
                com.jack.ai.MainActivity.lastActive = android.os.SystemClock.elapsedRealtime()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
        }).authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("解锁 Kavi").setAllowedAuthenticators(kinds).build())
    }

    override fun onResume() {
        super.onResume()
        if (locked) unlock()
        JackBrowser.userViewing = true
        JackBrowser.reclaim(this)
    }

    /** 手机内存紧：看不见的标签页先休眠，免得系统把整个 App 杀掉。 */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) JackBrowser.sleepAllBackground()
    }

    override fun onPause() {
        super.onPause()
        if (!locked) com.jack.ai.MainActivity.lastActive = android.os.SystemClock.elapsedRealtime()
        JackBrowser.userViewing = false
        JackBrowser.backgroundPlayback()
        // 刚登录完 App 就被系统杀掉也不丢登录：离开浏览器时把 Cookie 写到磁盘
        JackBrowser.flushCookies()
        // 标签页也记下来，下次打开 App 还在
        JackBrowser.saveTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (JackBrowser.address.isNotEmpty()) outState.putString(EXTRA_URL, JackBrowser.address)
    }

    /** 全屏看视频时按 Home 或切走：缩成画中画小窗接着放。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (JackBrowser.fullscreen != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    private fun pipParams(): android.app.PictureInPictureParams =
        android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).apply {
            // 新系统：全屏视频时切走自动进小窗（手势动画更顺）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(JackBrowser.fullscreen != null)
        }.build()

    /** 画中画开着。 */
    private var inPip by mutableStateOf(false)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!locked) com.jack.ai.MainActivity.lastActive = android.os.SystemClock.elapsedRealtime()
        // 画中画小窗被划掉：视频停下，退出全屏
        if (inPip) { inPip = false; JackBrowser.pauseAll(); exitFullscreen() }
    }

    override fun onDestroy() {
        // 用户自己关掉浏览器：网页里在放的声音也停（只是切到后台时接着放）
        if (isFinishing) { JackBrowser.pauseAll(); PageReader.stop() }
        JackBrowser.userViewing = false
        if (JackBrowser.chooser == ::chooseFiles) JackBrowser.chooser = null
        if (JackBrowser.systemPerms == ::systemPerms) JackBrowser.systemPerms = null
        JackBrowser.permAsk?.answer?.invoke(false, false)
        permWaiting?.second?.invoke(false); permWaiting = null
        if (JackBrowser.askSelection == ::askAboutSelection) JackBrowser.askSelection = null
        fileCallback?.onReceiveValue(null); fileCallback = null
        JackBrowser.fullscreen?.second?.onCustomViewHidden(); JackBrowser.fullscreen = null
        JackBrowser.release(this)
        JackBrowser.clearProxyIfIdle()
        super.onDestroy()
    }

    private fun open(input: String) {
        val url = BrowserNet.normalize(input, proxiedNow(), JackBrowser.searchEngine)
        if (url.isEmpty()) return
        editing = url
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.EXTERNAL -> JackBrowser.external = url
            else -> lifecycleScope.launch { JackBrowser.load(this@BrowserActivity, url, agent = false) }
        }
    }

    /** 新开一个标签页打开网址；开满了就在当前页打开。 */
    private fun openInNewTab(url: String?, incognito: Boolean = false, account: Int = 0) {
        val b = JackBrowser
        b.webView(this)
        finding = null
        if (b.tabs.size >= JackBrowser.MAX_TABS) b.notice = "最多开 ${JackBrowser.MAX_TABS} 个标签页，这次在当前页打开。可以点底栏的数字关掉几个。"
        else {
            b.newTab(incognito = incognito, account = account, group = tabGroup?.takeIf { it in b.groups() }.orEmpty())
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
        // 网页跳转后地址栏跟着变（用户正在改的时候除外：改完按前往才算）
        LaunchedEffect(b.address) { editing = b.address }
        // 切到恢复回来、还没加载的标签页：这时才打开
        LaunchedEffect(b.current, b.tabs.size) { b.tab.restore?.let { url -> open(url) } }
        val focus = LocalFocusManager.current
        // 视频全屏时藏起状态栏和导航栏
        val full = b.fullscreen
        LaunchedEffect(full != null) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { setPictureInPictureParams(pipParams()) }
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
                showDownloads -> showDownloads = false
                showLater -> showLater = false
                showHistory -> showHistory = false
                offlineView != null -> offlineView = null
                showOffline -> showOffline = false
                showMarks -> showMarks = false
                editScript != null -> editScript = null
                showScripts -> showScripts = false
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
        // 往下翻网页时收起地址栏和底栏；在打字、开着菜单、起始页、出错时一直显示
        val bars = !b.barsHidden || typing || menu || finding != null || b.address.isEmpty() || b.pageFailed || BrowserChat.open
        val bottom = addressAtBottom
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!bottom) {
                AnimatedVisibility(bars) { AddressRow() }
                Suggestions(reversed = false)
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
            // 只在 Kavi 用的就是眼前这一页时提示（它在自己的后台标签页里做事时，这一页随便用）
            if (b.agentActive() && (com.jack.ai.tools.BrowserLock.visibleInUse() || com.jack.ai.tools.BrowserLock.isAgentTab(b.tab))) {
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
                // 从边缘滑：拉够了圆圈变实，松手就后退 / 前进
                b.edgeSwipe?.let { (side, p) ->
                    val can = if (side < 0) b.canBack else b.canForward
                    Box(
                        Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = (4 + 20 * p).dp).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (p >= 1f && can) colors.primary else colors.surfaceVariant.copy(alpha = 0.6f + 0.4f * p)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (side < 0) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                            if (side < 0) "后退" else "前进",
                            tint = if (p >= 1f && can) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = if (can) 1f else 0.4f))
                    }
                }
                // 问 Kavi：浮在网页右下角，单手点得到
                if (bars && b.address.isNotEmpty() && !BrowserChat.open && !b.pageFailed) androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { BrowserChat.open = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                ) { Icon(Icons.Default.Face, "问 Kavi 这一页", tint = if (BrowserChat.busy) colors.primary else colors.onPrimaryContainer) }
            }
            if (PageReader.active) ReadingBar()
            b.downloading?.let { msg ->
                Text(msg, Modifier.fillMaxWidth().clickable { showDownloads = true }.background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.picking) PickBar()
            if (b.teaching) TeachBar()
            if (BrowserChat.open) ChatPanel()
            if (bottom) {
                Suggestions(reversed = true)
                AnimatedVisibility(bars) { AddressRow() }
            }
            AnimatedVisibility(bars && !(bottom && typing)) { NavBar() }
        }
        if (menu) MenuSheet()
        if (showTabs) Box(Modifier.fillMaxSize().safeDrawingPadding()) { TabsPanel() }
        if (showDownloads && b.userViewing) Box(Modifier.fillMaxSize().safeDrawingPadding()) { BrowserDownloadsPanel { showDownloads = false } }
        if (showLater && b.userViewing) Box(Modifier.fillMaxSize().safeDrawingPadding()) { LaterPanel() }
        LaunchedEffect(b.pageFailed, b.address) {
            if (!b.pageFailed || !b.userViewing || b.tab.incognito) return@LaunchedEffect
            // 存过整页的先用整页，没有再用阅读列表存的正文
            val page = b.offline.firstOrNull { it.url == b.address && b.offlineFile(it).exists() }
            if (page != null) { offlineView = page; b.notice = "网络打不开，显示存下的离线网页" }
            else b.offlineLater(b.address)?.let { reader = it; b.notice = "网络打不开，显示已保存的正文" }
        }
        if (showHistory) Box(Modifier.fillMaxSize().safeDrawingPadding()) { HistoryPanel() }
        if (showMarks && b.userViewing) Box(Modifier.fillMaxSize().safeDrawingPadding()) { MarksPanel() }
        if (showOffline && b.userViewing) Box(Modifier.fillMaxSize().safeDrawingPadding()) { OfflinePanel() }
        offlineView?.let { p -> Box(Modifier.fillMaxSize().safeDrawingPadding()) { OfflineViewer(p) } }
        if (showScripts && b.userViewing) Box(Modifier.fillMaxSize().safeDrawingPadding()) { ScriptsPanel() }
        editScript?.let { sc -> Box(Modifier.fillMaxSize().safeDrawingPadding()) { ScriptEditor(sc) } }
        // 视频全屏：盖满整个屏幕
        full?.let { (view, _) ->
            // 碰一下屏幕，顶上露出倍速按钮，三秒后藏起来
            var chip by remember { mutableStateOf(true) }
            var pokes by remember { mutableIntStateOf(0) }
            LaunchedEffect(pokes) { kotlinx.coroutines.delay(3000); chip = false }
            Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); chip = true; pokes++ } }
            }) {
                AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
                AnimatedVisibility(chip, Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                    Text("倍速 " + BrowserPages.rateText(b.tab.rate),
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f))
                            .clickable { ratePick = true }.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        }
        b.pressed?.let { p -> PressMenu(p) }
        b.permAsk?.let { a -> PermDialog(a) }
        if (shooting && b.userViewing) AlertDialog(
            onDismissRequest = { shotJob?.cancel() }, title = { Text("正在截图…") },
            text = { Text("正在逐屏加载并拼接，最多二十屏。") },
            confirmButton = { TextButton(onClick = { shotJob?.cancel() }) { Text("取消") } },
        )
        longShot?.takeIf { b.userViewing }?.let { saved -> AlertDialog(
            onDismissRequest = { longShot = null }, title = { Text("长截图已存到相册") }, text = { Text(saved.where) },
            confirmButton = { TextButton(onClick = {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    .onFailure { b.notice = "手机上没有能打开图片的 App" }
            }) { Text("打开") } },
            dismissButton = { Row {
                TextButton(onClick = { runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(saved.mime).putExtra(Intent.EXTRA_STREAM, saved.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享长截图")) } }) { Text("分享") }
                TextButton(onClick = { longShot = null }) { Text("关闭") }
            } },
        ) }
        if (searchPick && b.userViewing) AlertDialog(
            onDismissRequest = { searchPick = false }, title = { Text("搜索引擎") },
            text = { Column { listOf("auto" to "自动", "google" to "Google", "bing" to "必应", "baidu" to "百度", "duckduckgo" to "DuckDuckGo").forEach { (key, label) ->
                Text((if (b.searchEngine == key) "✓ " else "    ") + label,
                    Modifier.fillMaxWidth().clickable { b.changeSearchEngine(key); searchPick = false }.padding(vertical = 12.dp))
            } } }, confirmButton = { TextButton(onClick = { searchPick = false }) { Text("关闭") } },
        )
        siteData?.let { d ->
            AlertDialog(
                onDismissRequest = { siteData = null }, title = { Text(d.root) },
                text = { Text(
                    "Cookie（登录状态）：${d.cookies} 个\n网页存储：${com.jack.ai.api.BrowserSiteData.sizeText(d.storage)}" +
                        (if (d.origins.size > 1) "（${d.origins.size} 个子网站）" else "") +
                        "\n\n清掉后这个网站会退出登录、忘掉设置，别的网站不受影响。" +
                        (if (b.tab.account > 0) "\n只清「分身${b.tab.account}」里的。" else ""),
                    style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = {
                    siteData = null
                    lifecycleScope.launch { b.clearSite(d); b.notice = "已清掉 ${d.root} 的登录状态和网页存储。" }
                }) { Text("清掉这个网站的", color = colors.error) } },
                dismissButton = { TextButton(onClick = { siteData = null }) { Text("关闭") } },
            )
        }
        if (ratePick) AlertDialog(
            onDismissRequest = { ratePick = false }, title = { Text("视频倍速") },
            text = { Column {
                Text("这个标签页里的视频、音频都按这个速度放，换网页也保持。长按正在放的视频能临时快进（2 倍，松手恢复）。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                BrowserPages.VIDEO_RATES.forEach { r ->
                    Text((if (b.tab.rate == r) "✓ " else "    ") + BrowserPages.rateText(r) + if (r == 1f) "（正常）" else "",
                        Modifier.fillMaxWidth().clickable { ratePick = false; b.changeRate(r) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            } }, confirmButton = { TextButton(onClick = { ratePick = false }) { Text("关闭") } },
        )
        shortcutAsk?.let { (url, first) ->
            var name by remember(url) { mutableStateOf(first.take(24)) }
            AlertDialog(
                onDismissRequest = { shortcutAsk = null }, title = { Text("添加到主屏幕") },
                text = { Column {
                    Text(url.take(120), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("图标名字") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } },
                confirmButton = { TextButton(onClick = {
                    shortcutAsk = null
                    val ok = WebShortcut.pin(this@BrowserActivity, url, name, b.tabs.firstOrNull { it.address == url }?.icon)
                    b.notice = if (ok) "已请桌面添加「${name.trim().ifBlank { WebShortcut.host(url) }}」。\n\n桌面上没出现的话：到 系统设置 → 应用 → Kavi → 权限 里打开「桌面快捷方式」，再加一次。"
                        else "桌面没让加。可以到 系统设置 → 应用 → Kavi → 权限 里打开「桌面快捷方式」再试。"
                }) { Text("添加") } },
                dismissButton = { TextButton(onClick = { shortcutAsk = null }) { Text("取消") } },
            )
        }
        if (textPick) ZoomDialog("网页字号", "网页里的字太大或太小时调一下，所有网页都用这个字号。排版不变，只改字。",
            BrowserPages.TEXT_ZOOMS, b.textZoom, { textPick = false }) { b.changeTextZoom(it) }
        if (zoomPick) AlertDialog(
            onDismissRequest = { zoomPick = false },
            title = { Text("电脑版放大") },
            text = {
                Column {
                    Text("电脑版的字和按钮太小时放大一点。和电脑上按 Ctrl + 加号一样，页面会按窄一点排，所有网站都用这个比例。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    BrowserPages.DESKTOP_ZOOMS.forEach { z ->
                        Text((if (z == b.desktopZoom) "✓ " else "    ") + "$z%" + if (z == 100) "（原样）" else "",
                            Modifier.fillMaxWidth().clickable { zoomPick = false; b.changeDesktopZoom(z) }.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { zoomPick = false }) { Text("关闭") } },
        )
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
                extra = listOf(
                    "让 Kavi 总结" to { reader = null; askKavi("总结一下这篇") },
                    "朗读" to { PageReader.start(this@BrowserActivity, if (b.tab.incognito) "隐身标签页" else title.ifBlank { b.title }, md) },
                ),
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
                            Text(report, style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont))
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

    /** 地址栏：不打字时显示网站名（前面是锁或警告，点了看这个网站的数据），打字时选中整个网址方便直接改。 */
    @Composable
    private fun AddressRow() {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(editing)) }
        LaunchedEffect(typing) { if (typing) field = androidx.compose.ui.text.input.TextFieldValue(editing, androidx.compose.ui.text.TextRange(0, editing.length)) }
        LaunchedEffect(editing) { if (typing && field.text != editing) field = androidx.compose.ui.text.input.TextFieldValue(editing, androidx.compose.ui.text.TextRange(editing.length)) }
        val shown = if (typing) field else androidx.compose.ui.text.input.TextFieldValue(
            b.tab.badge + if (b.address.isEmpty()) "" else BrowserPages.host(b.address))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextField(
                value = shown,
                onValueChange = { field = it; editing = it.text },
                placeholder = { Text("搜索或输入网址") },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); open(editing) }),
                textStyle = MaterialTheme.typography.bodyLarge,
                leadingIcon = {
                    val https = b.address.startsWith("https://")
                    IconButton(enabled = b.address.isNotEmpty() && !typing, onClick = {
                        lifecycleScope.launch { b.siteData()?.let { siteData = it } }
                    }) {
                        when {
                            typing || b.address.isEmpty() -> Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                            https -> Icon(Icons.Default.Lock, "加密连接，点了看这个网站的数据", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            else -> Icon(Icons.Default.Warning, "不加密，点了看这个网站的数据", Modifier.size(18.dp), tint = colors.error)
                        }
                    }
                },
                trailingIcon = {
                    Row {
                        if (typing || b.address.isEmpty()) {
                            IconButton(onClick = { focus.clearFocus(); startVoice() }) { Icon(AppIcons.Mic, "语音输入", Modifier.size(22.dp)) }
                            IconButton(onClick = { focus.clearFocus(); startScan() }) { Icon(AppIcons.QrScan, "扫二维码", Modifier.size(22.dp)) }
                        } else if (b.loading < 100) {
                            IconButton(onClick = { b.webView(this@BrowserActivity).stopLoading() }) { Icon(Icons.Default.Close, "停止加载", Modifier.size(22.dp)) }
                        } else IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新", Modifier.size(22.dp)) }
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(addressFocus).onFocusChanged { typing = it.isFocused },
            )
            if (typing) TextButton(onClick = { editing = b.address; focus.clearFocus() }) { Text("取消") }
        }
    }

    /** 地址栏打字时：书签、最近访问、浏览记录里对得上的。地址栏在底部时倒着排，最像的离手指最近。 */
    @Composable
    private fun Suggestions(reversed: Boolean) {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        if (!typing || editing.isBlank() || editing == b.address) return
        val list = b.suggest(editing).let { if (reversed) it.reversed() else it }
        Column(Modifier.fillMaxWidth().background(colors.surface)) {
            list.forEach { v ->
                Row(Modifier.fillMaxWidth().clickable { focus.clearFocus(); open(v.url) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (b.marks.any { it.url == v.url }) Icons.Default.Star else Icons.Default.DateRange, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(BrowserPages.label(v), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(v.url.substringAfter("://").removePrefix("www."), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /** 底栏：后退、前进、起始页、标签页、菜单，五个大按钮。长按标签页数字直接开新标签页。 */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun NavBar() {
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
            Btn("后退", b.canBack, onClick = { b.clearError(); b.webView(this@BrowserActivity).goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", Modifier.size(26.dp)) }
            Btn("前进", b.canForward, onClick = { b.clearError(); b.webView(this@BrowserActivity).goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进", Modifier.size(26.dp)) }
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
    private fun MenuSheet() {
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
                        lifecycleScope.launch { b.readable(this@BrowserActivity)?.let { reader = it } ?: run { b.notice = "这一页找不出正文（可能不是文章页，或者还没加载完）" } }
                    },
                    Tile(AppIcons.Globe, if (b.tab.translated) "看原文" else "翻译", has && b.tab.translating == null, b.tab.translated) {
                        if (b.tab.translated) b.showOriginal() else lifecycleScope.launch { b.translate(this@BrowserActivity)?.let { b.notice = it } }
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
                Item("存进资料库", has) { lifecycleScope.launch { b.notice = runCatching { b.saveToLibrary(this@BrowserActivity) }.getOrElse { "没存成：${it.message}" } } }
                Item("复制链接", has) { copy(b.address); b.notice = "已复制链接" }
                Item("添加到主屏幕", has && BrowserPages.isWeb(b.address) && !b.tab.incognito) {
                    if (WebShortcut.supported(this@BrowserActivity)) shortcutAsk = b.address to b.title.ifBlank { WebShortcut.host(b.address) }
                    else b.notice = "这个桌面不支持添加网页图标。"
                }
                Item("屏蔽网页上的东西", has, b.blocksHere().size.takeIf { it > 0 }?.let { "$it 处" }) {
                    if (b.blocksHere().isNotEmpty()) blockList = true else b.startPick()
                }
                Item("这个网站的数据", has && BrowserPages.isWeb(b.address)) {
                    lifecycleScope.launch { b.siteData()?.let { siteData = it } ?: run { b.notice = "看不出这是哪个网站" } }
                }
                if (has && b.permsHere()) Item("重置这个网站的权限") { b.resetPermsHere(); b.notice = "这个网站下次要用相机、麦克风或位置时会再问你。" }
                Item("用系统浏览器打开", has) { Links.system(this@BrowserActivity, b.address) }
                Item((if (b.tab.diagOn) "● " else "") + "网页诊断", has) { lifecycleScope.launch { diag = b.diagnose(this@BrowserActivity) } }

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
                    getSharedPreferences("browser", MODE_PRIVATE).edit().putBoolean("addressbottom", addressAtBottom).apply()
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
                    lifecycleScope.launch { b.notice = b.toggleProxySite(this@BrowserActivity) }
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

    private class Tile(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val enabled: Boolean, val on: Boolean = false, val onClick: () -> Unit)

    /** 正在朗读时底栏上面的一条：念到哪、语速、暂停 / 继续、停止。 */
    @Composable
    private fun ReadingBar() {
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
    private fun routeLabel(): String {
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
    private fun takeLongShot() {
        if (!JackBrowser.userViewing || shooting) return
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            shotPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        val w = JackBrowser.tab.web ?: return
        shooting = true
        shotJob = lifecycleScope.launch {
            try {
                val (saved, cut) = BrowserLongShot.capture(this@BrowserActivity, w)
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
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun TabsPanel() {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        val groups = b.groups()
        // 选着的分组被关光了：回到全部
        if (tabGroup != null && tabGroup !in groups) tabGroup = null
        val shown = b.tabs.indices.filter { tabGroup == null || b.tabs[it].group == tabGroup }
        val full = b.tabs.size >= JackBrowser.MAX_TABS
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showTabs = false }) { Icon(Icons.Default.Close, "收起") }
                    Text("标签页（${b.tabs.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (b.incognitoSupported()) {
                        TextButton(enabled = !full, onClick = { accountPick = true }) { Text("分身") }
                        TextButton(enabled = !full, onClick = { showTabs = false; openInNewTab(null, incognito = true) }) { Text("隐身") }
                    }
                    TextButton(enabled = !full, onClick = { showTabs = false; openInNewTab(null) }) { Text("新标签页") }
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
                    items(shown.size, key = { b.tabs[shown[it]].id }) { k ->
                        val i = shown[k]
                        val t = b.tabs[i]
                        val on = i == b.current
                        Column(
                            Modifier.clip(Radii.s)
                                .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outlineVariant, Radii.s)
                                .combinedClickable(onClick = { switchTab(i) }, onLongClick = { tabMove = t }),
                        ) {
                            Row(Modifier.fillMaxWidth().background(if (on) colors.primaryContainer else colors.surfaceVariant).padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(t.badge + t.title.ifBlank { if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address) }, Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant)
                                IconButton(onClick = { b.closeTab(i); if (b.tabs.isEmpty()) showTabs = false }, Modifier.size(36.dp)) {
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
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
    private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
        val colors = MaterialTheme.colorScheme
        Text(label, Modifier.clip(Radii.s).border(1.dp, if (selected) colors.primary else colors.outlineVariant, Radii.s)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface, maxLines = 1)
    }

    /** 选一个百分比的小弹窗（网页字号）。 */
    @Composable
    private fun ZoomDialog(title: String, note: String, options: List<Int>, now: Int, close: () -> Unit, pick: (Int) -> Unit) {
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

    private fun openLater(e: BrowserPages.Later) {
        showLater = false
        JackBrowser.markLaterRead(e.url)
        open(e.url)
    }

    @Composable
    private fun LaterPanel() {
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
    private fun HistoryPanel() {
        val b = JackBrowser
        val colors = MaterialTheme.colorScheme
        var all by remember { mutableStateOf<List<com.jack.ai.data.HistoryEntry>?>(null) }
        var query by remember { mutableStateOf("") }
        var confirmClear by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { all = b.historyEntries() }
        val now = remember { System.currentTimeMillis() }
        val groups = remember(all, query) { com.jack.ai.data.BrowserHistory.byDay(all.orEmpty(), query, now) }
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
    private fun MarksPanel() {
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
    private fun OfflinePanel() {
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
    private fun OfflineViewer(p: BrowserPages.OfflinePage) {
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
    private fun ScriptsPanel() {
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
                    OutlinedTextField(want, { want = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("比如：自动展开全文，去掉底部的「打开 App」") }, minLines = 2)
                } },
                confirmButton = { TextButton(enabled = want.isNotBlank(), onClick = {
                    ask = null; showScripts = false
                    BrowserChat.open = true
                    BrowserChat.send(this@BrowserActivity, "给" + (if (site.isEmpty()) "网页" else "这个网站（$site）") + "写一个网页脚本，用 browser 的 save_script 存下来：" + want.trim())
                }) { Text("交给 Kavi") } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text("取消") } },
            )
        }
    }

    /** 改一个网页脚本：名字、网站、代码。贴进来的油猴脚本自动读出名字和网站。 */
    @Composable
    private fun ScriptEditor(start: com.jack.ai.api.UserScripts.Script) {
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
    private fun ErrorPage(msg: String) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("网页没打开", style = MaterialTheme.typography.titleMedium)
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reload() }) { Text("重试") }
                JackBrowser.tab.httpFallback?.let { http -> OutlinedButton(onClick = { open(JackBrowser.allowHttp(http)) }) { Text("仍然打开") } }
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
                        Text("稍后读", Modifier.fillMaxWidth().clickable(enabled = !b.tab.incognito) { close(); b.addLater(link) }.padding(vertical = 12.dp),
                            color = if (b.tab.incognito) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface)
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

    /** 网页要用相机、麦克风、位置：问用户。允许 / 不允许会按网站记住（隐身页不记），「只这次」不记。 */
    @Composable
    private fun PermDialog(a: JackBrowser.PermAsk) {
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
    private fun isDefaultBrowser(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        else packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName

    private val askDefault = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        defaultBrowser = isDefaultBrowser()
        if (defaultBrowser) JackBrowser.notice = "好了：别的 App 里点网页链接，会直接用 Kavi 浏览器打开。"
    }
    private var defaultBrowser by mutableStateOf(true)

    /** 设成默认浏览器：新系统弹系统自己的确认框，旧系统打开「默认应用」设置。 */
    private fun makeDefault() {
        val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_BROWSER) }?.createRequestRoleIntent(RoleManager.ROLE_BROWSER) else null
        val intent = role ?: Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        runCatching { askDefault.launch(intent) }.onFailure { JackBrowser.notice = "打不开系统设置。可以到 设置 → 应用 → 默认应用 → 浏览器 里选 Kavi。" }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun StartPage() {
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
