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

/**
 * Kavi 浏览器的完整页面：用户自己翻网页，或者看着 Kavi 操作、随时接手。网页窗口和 Kavi 用的是同一个（JackBrowser）。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
open class BrowserActivity : androidx.fragment.app.FragmentActivity() {
    /** 从 Kavi 的底栏、侧边栏点进来的（[BrowserTabActivity]）：留在 Kavi 里，底下带底栏。桌面上单独的浏览器图标进来的是 false。 */
    protected open val inKaviTabs = false
    /** 点底栏去了别的页面：这不是「关掉浏览器」，网页里在放的声音接着放。 */
    private var leavingForTab = false

    /** 底栏上点了别的格子：回到 Kavi 的那一页，淡入淡出。 */
    private fun goTab(t: KaviTab) {
        if (t == KaviTab.BROWSER) return
        leavingForTab = true
        startActivity(Intent(this, com.jack.ai.MainActivity::class.java).setAction(com.jack.ai.MainActivity.ACTION_OPEN_TAB).putExtra("tab", t.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
        finish()
    }

    private lateinit var settings: Settings
    internal var editing by mutableStateOf("")
    /** 标签页列表开着。 */
    internal var showTabs by mutableStateOf(false)
    /** 识别出的二维码内容，等弹窗。 */
    internal var qr by mutableStateOf<String?>(null)
    /** 管理这个网站屏蔽的东西。 */
    internal var blockList by mutableStateOf(false)
    /** 自动填密码的说明。 */
    internal var autofillInfo by mutableStateOf(false)
    /** 选网页字号。 */
    internal var textPick by mutableStateOf(false)
    /** 历史记录页开着。 */
    internal var shotJob: kotlinx.coroutines.Job? = null
    internal var shooting by mutableStateOf(false)
    internal var longShot by mutableStateOf<com.jack.ai.data.Downloads.Saved?>(null)
    internal val shotPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takeLongShot() else JackBrowser.notice = "没有相册存储权限，无法保存长截图"
    }
    internal var showDownloads by mutableStateOf(false)
    internal var showLater by mutableStateOf(false)
    internal var searchPick by mutableStateOf(false)
    internal var showHistory by mutableStateOf(false)
    /** 选电脑版放大多少。 */
    internal var zoomPick by mutableStateOf(false)
    /** 选开哪个分身。 */
    internal var accountPick by mutableStateOf(false)
    /** 阅读模式：(标题, 正文 Markdown)。 */
    internal var reader by mutableStateOf<Pair<String, String>?>(null)
    /** 选视频倍速。 */
    internal var ratePick by mutableStateOf(false)
    /** 菜单面板开着；地址栏正在打字。 */
    internal var menu by mutableStateOf(false)
    private var typing by mutableStateOf(false)
    /** 地址栏放在底部（单手够得着）还是顶上。 */
    internal var addressAtBottom by mutableStateOf(true)
    internal val addressFocus = androidx.compose.ui.focus.FocusRequester()
    /** 网页脚本列表开着；正在改的脚本（新建的 id 是新的）。 */
    internal var showScripts by mutableStateOf(false)
    internal var editScript by mutableStateOf<com.jack.ai.api.UserScripts.Script?>(null)
    /** 标签页列表只看哪个分组（null = 全部）；长按的标签页（换分组）；分组的菜单（"" = 全部）；新建 / 改名分组。 */
    internal var tabGroup by mutableStateOf<String?>(null)
    internal var tabMove by mutableStateOf<JackBrowser.Tab?>(null)
    internal var groupMenu by mutableStateOf<String?>(null)
    internal var groupName by mutableStateOf<Pair<String, JackBrowser.Tab?>?>(null)
    /** 书签页、离线网页列表开着；正在看的离线网页。 */
    internal var showMarks by mutableStateOf(false)
    internal var showOffline by mutableStateOf(false)
    internal var offlineView by mutableStateOf<BrowserPages.OfflinePage?>(null)
    /** 这个网站的数据（null = 没打开）。 */
    internal var siteData by mutableStateOf<JackBrowser.SiteData?>(null)

    /** 导入别的浏览器导出的 bookmarks.html。 */
    internal val importMarks = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
    internal val exportMarks = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
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
    internal var shortcutAsk by mutableStateOf<Pair<String, String>?>(null)

    /** 地址栏的语音输入：说完直接打开（网址就去，别的就搜）。 */
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { open(it) }
    }
    /** 地址栏的扫一扫：网址直接打开，别的弹出来能复制。 */
    private val scan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val text = r.contents?.trim()?.takeIf { it.isNotEmpty() } ?: return@registerForActivityResult
        if (BrowserPages.isWeb(text)) open(text) else qr = text
    }

    internal fun startVoice() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说要搜的内容或网址")
        runCatching { voice.launch(i) }.onFailure { JackBrowser.notice = "手机没有语音识别服务，可以用输入法自带的语音输入" }
    }

    internal fun startScan() {
        runCatching {
            scan.launch(com.journeyapps.barcodescanner.ScanOptions().setPrompt("扫网址二维码").setBeepEnabled(false)
                .setOrientationLocked(false).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE))
        }.onFailure { JackBrowser.notice = "打不开扫码：${it.message}" }
    }

    /** 朗读这页：取正文念（隐身页通知栏上不显示标题）。 */
    internal fun readAloud() {
        val b = JackBrowser
        lifecycleScope.launch {
            val page = b.readable(this@BrowserActivity)
            if (page == null) { b.notice = "这一页找不出能念的正文（可能不是文章页，或者还没加载完）"; return@launch }
            val title = if (b.tab.incognito) "隐身标签页" else page.first.ifBlank { b.title }
            PageReader.start(this@BrowserActivity, title, page.second)
        }
    }
    /** 网页诊断报告（null = 没打开）。 */
    internal var diag by mutableStateOf<String?>(null)
    /** 页内查找：null 没在找，否则是要找的字。 */
    internal var finding by mutableStateOf<String?>(null)

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

    internal fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

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
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon, settings.fontChoice) {
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
        // 两个浏览器入口都开着时，眼前这个接管选文件、权限这些回调（另一个关掉时会清空它们）
        JackBrowser.chooser = ::chooseFiles
        JackBrowser.systemPerms = ::systemPerms
        JackBrowser.askSelection = ::askAboutSelection
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
        if (isFinishing && !leavingForTab) { JackBrowser.pauseAll(); PageReader.stop() }
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

    internal fun open(input: String) {
        val url = BrowserNet.normalize(input, proxiedNow(), JackBrowser.searchEngine)
        if (url.isEmpty()) return
        editing = url
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.EXTERNAL -> JackBrowser.external = url
            else -> lifecycleScope.launch { JackBrowser.load(this@BrowserActivity, url, agent = false) }
        }
    }

    /** 新开一个标签页打开网址；开满了就在当前页打开。 */
    internal fun openInNewTab(url: String?, incognito: Boolean = false, account: Int = 0) {
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

    internal fun switchTab(i: Int) {
        if (finding != null) closeFind()
        JackBrowser.switchTo(i)
        showTabs = false
    }

    internal fun reload() {
        val url = JackBrowser.address.ifEmpty { editing }
        if (url.isNotBlank()) open(url)
    }

    /** 现在打开新网页会不会走代理（决定搜索用 Google 还是必应）。 */
    internal fun proxiedNow(): Boolean = JackBrowser.route != BrowserNet.DIRECT && Net.config.enabled

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
        // （眼前这页的网页窗口被重建时 fresh 会变，也再看一眼：位置没变但窗口换了的情况）
        LaunchedEffect(b.current, b.tabs.size, b.tab.fresh) { b.tab.restore?.let { url -> open(url) } }
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
                // 起始页一直盖到新网页真显示出来（地址有了）为止：网页窗口这时是一整块空白，露出来就是灰的一屏。
                // 网页自己弹的窗口不盖（它往空白页里直接写内容）
                if (!b.pageFailed && b.tab.openedBy == null) {
                    if (b.address.isEmpty()) StartPage()
                    // 恢复的、休眠醒来的标签页：网页还没显示出来之前，网页窗口同样是空白的
                    else if (b.tab.fresh) OpeningPage(b.address)
                }
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
            // 从 Kavi 里点进来的：底下也是那条底栏，和别的页面一样点一下就换
            val tabs = KaviTab.bar(settings.tabBar)
            if (inKaviTabs && KaviTab.BROWSER in tabs) AnimatedVisibility(bars && !typing) {
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
                    LiquidTabBar(tabs.map { t -> TabItem(t.icon, t.label, selected = t == KaviTab.BROWSER) { goTab(t) } }, tabs.indexOf(KaviTab.BROWSER))
                }
            }
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

    internal class Tile(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val enabled: Boolean, val on: Boolean = false, val onClick: () -> Unit)

    internal val askDefault = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        defaultBrowser = isDefaultBrowser()
        if (defaultBrowser) JackBrowser.notice = "好了：别的 App 里点网页链接，会直接用 Kavi 浏览器打开。"
    }
    internal var defaultBrowser by mutableStateOf(true)

    companion object {
        const val EXTRA_URL = "url"
    }
}

/**
 * 留在 Kavi 里的浏览器：和 [BrowserActivity] 一样，只是不单独开一个任务（不会像切到另一个 App 那样跳走），
 * 进出淡入淡出，底下带 Kavi 的底栏。桌面上单独的「Kavi 浏览器」图标、别的 App 点进来的链接还是用 [BrowserActivity]。
 */
class BrowserTabActivity : BrowserActivity() {
    override val inKaviTabs = true

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        fun open(ctx: android.content.Context) {
            val i = Intent(ctx, BrowserTabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val opts = android.app.ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i, opts)
        }
    }
}
