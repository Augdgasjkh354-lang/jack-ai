package com.jack.ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jack.ai.data.Project
import com.jack.ai.data.hasAnyKey
import com.jack.ai.ui.ChatScreen
import com.jack.ai.ui.JackTheme
import com.jack.ai.ui.LibraryScreen
import com.jack.ai.ui.TablesScreen
import com.jack.ai.ui.LockScreen
import com.jack.ai.ui.MemoryScreen
import com.jack.ai.ui.ProjectScreen
import com.jack.ai.ui.RenameDialog
import com.jack.ai.ui.ReviewScreen
import com.jack.ai.ui.RoutinesScreen
import com.jack.ai.ui.SettingsScreen
import com.jack.ai.ui.SkillsScreen
import com.jack.ai.ui.UsageScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

sealed interface Screen {
    data object Settings : Screen
    data object Memory : Screen
    data object Skills : Screen
    data object Usage : Screen
    data object Routines : Screen
    data object Library : Screen
    data object Tables : Screen
    data object Review : Screen
    data object Apps : Screen
    data object Downloads : Screen
    data object Files : Screen
    data class ProjectPage(val id: String) : Screen
}

class MainActivity : FragmentActivity() {
    private val vm: ChatViewModel by viewModels()

    /** 正式版第一次打开、还没同意用户协议和隐私政策：只显示同意页，主界面和 ChatViewModel 都不建（见 Legal）。 */
    private var waitingConsent = false

    /** 开了应用锁时，是否需要解锁。 */
    private var locked by mutableStateOf(false)
    private val stack = mutableStateListOf<Screen>()

    override fun onStart() {
        super.onStart()
        if (waitingConsent) return
        // 离开超过 30 秒再回来，重新上锁
        if (vm.state.value.settings.appLock && (lastActive == 0L || SystemClock.elapsedRealtime() - lastActive > 30_000)) {
            locked = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (waitingConsent) return
        if (!locked) lastActive = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        if (waitingConsent) return
        if (!locked) lastActive = SystemClock.elapsedRealtime()
        visible = false
        Widgets.refreshAll(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        if (waitingConsent) return
        visible = true
        Widgets.refreshAll(applicationContext)
        // 后台做梦可能更新了记忆
        vm.reloadMemory()
        vm.reloadLibrary()
        vm.reloadRoutines()
        vm.mergeInbox()
        if (locked) unlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (waitingConsent) return
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 从分享菜单的「存进 Kavi 收件箱」进来：直接交给收件箱自动归档
        if (intent?.component?.className?.endsWith(".InboxShare") == true &&
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            stack.clear()
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                .joinToString("\n").takeIf { it.isNotBlank() }
            val uris = if (intent.action == Intent.ACTION_SEND) listOfNotNull(streamExtra(intent)) else streamListExtra(intent)
            vm.inboxShare(text, uris, intent.type)
            return
        }
        // 从分享菜单的「用 Kavi 总结」进来：链接、视频或录音文件，Kavi 转文字再总结
        if (intent?.component?.className?.endsWith(".SummarizeShare") == true && intent.action == Intent.ACTION_SEND) {
            stack.clear()
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                .joinToString("\n").takeIf { it.isNotBlank() }
            vm.summarizeShare(text, listOfNotNull(streamExtra(intent)))
            return
        }
        // 从分享菜单的「用 Kavi 下载」进来：开个新对话，让 Kavi 把链接里的视频或文件存到手机
        if (intent?.component?.className?.endsWith(".DownloadShare") == true && intent.action == Intent.ACTION_SEND) {
            stack.clear()
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                .joinToString("\n").takeIf { it.isNotBlank() }
            if (text != null) vm.downloadShare(text)
            return
        }
        when (intent?.action) {
            ACTION_NEW_CHAT -> {
                stack.clear(); vm.newChat()
                intent.getStringExtra("prefill")?.let { vm.receiveText(it) }
            }
            // Chat 和 Work 合并了：以前钉在桌面上的「新 Work」也开新对话
            ACTION_NEW_WORK -> { stack.clear(); vm.newChat() }
            // 悬浮球或长按电源键叫出来：带上当前屏幕开个新对话
            com.jack.ai.a11y.ScreenAsk.ACTION -> {
                stack.clear()
                // 这个入口别的 App 也能调：文件只认 Kavi 自己截屏时存的那个文件夹里的
                vm.askScreen(intent.getStringExtra("app").orEmpty(), screenAskFile(intent.getStringExtra("text")), screenAskFile(intent.getStringExtra("shot")))
            }
            ACTION_CAMERA -> { stack.clear(); vm.newChat(); vm.requestCamera() }
            ACTION_DOWNLOADS -> { stack.clear(); stack.add(Screen.Downloads) }
            ACTION_OPEN_CHAT -> { stack.clear(); vm.mergeInbox(intent.getStringExtra("convId")) }
            ACTION_VOICE -> { stack.clear(); vm.newChat(); vm.requestVoice() }
            ACTION_RECORD -> { stack.clear(); if (RecordService.current == null) { vm.newChat(); vm.requestRecord() } }
            // 通知栏点了「停止并整理」：停下，录好的由聊天页交给 Kavi
            ACTION_RECORD_STOP -> { stack.clear(); RecordService.stop(this) }
            Intent.ACTION_SEND -> {
                stack.clear()
                val uri = streamExtra(intent)
                vm.handleShare(
                    listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                        .joinToString("\n").takeIf { it.isNotBlank() },
                    listOfNotNull(uri),
                    intent.type,
                )
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                stack.clear()
                vm.handleShare(null, streamListExtra(intent), intent.type)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM))?.takeIf { sharedFromOutside(it, intent) }

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty().filter { sharedFromOutside(it, intent) }

    /**
     * 分享进来的地址能不能收：Kavi 读得到自己的私有文件（聊天记录、设置、Key），别的 App 要是递一个指向它们的地址
     * （file:// 指到 Kavi 的数据目录，或 Kavi 自己的 content://），Kavi 会把私有文件当成附件放进对话，一发就交给了模型。
     * 正常分享都是对方 App 自己的 content://，不受影响；Kavi 自己的浏览器带着 [INTERNAL_TOKEN]，照收。
     */
    private fun sharedFromOutside(uri: Uri, intent: Intent): Boolean =
        (intent.getStringExtra(EXTRA_INTERNAL) == INTERNAL_TOKEN ||
            ShareGuard.outside(uri.scheme, uri.authority, uri.path, packageName, listOfNotNull(java.io.File(applicationInfo.dataDir), getExternalFilesDir(null)?.parentFile)))
            .also { ok -> if (!ok) android.widget.Toast.makeText(this, "不能从这里打开 Kavi 自己的私有文件", android.widget.Toast.LENGTH_SHORT).show() }

    /** 「问这一屏」带来的文件：只认 [com.jack.ai.a11y.ScreenAsk] 存截屏的那个文件夹里的。 */
    private fun screenAskFile(path: String?): String? = path?.takeIf { p ->
        runCatching {
            val f = java.io.File(p).canonicalFile
            f.isFile && f.parentFile?.canonicalPath == java.io.File(cacheDir, "screen_ask").canonicalPath
        }.getOrDefault(false)
    }

    fun canLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private var prompting = false

    fun unlock() {
        if (prompting) return
        if (!canLock()) { locked = false; return }
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                locked = false
                lastActive = SystemClock.elapsedRealtime()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁 Kavi")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        if (com.jack.ai.ui.Legal.mustAsk(this)) {
            waitingConsent = true
            enableEdgeToEdge()
            setContent {
                JackTheme(com.jack.ai.data.ThemeMode.SYSTEM) {
                    com.jack.ai.ui.ConsentScreen(
                        onAgree = { com.jack.ai.ui.Legal.agree(this); waitingConsent = false; recreate() },
                        onExit = { finishAndRemoveTask() },
                    )
                }
            }
            return
        }
        val lastCrash = CrashLog.take(this)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        if (!vm.state.value.settings.hasAnyKey() && savedInstanceState == null) stack.add(Screen.Settings)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            var creatingProject by remember { mutableStateOf(false) }
            var crash by remember { mutableStateOf(lastCrash) }
            crash?.let { text ->
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { crash = null },
                    title = { androidx.compose.material3.Text("上次闪退了") },
                    text = {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            clip.setText(androidx.compose.ui.text.AnnotatedString(text)); crash = null
                        }) { androidx.compose.material3.Text("复制") }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { crash = null }) { androidx.compose.material3.Text("关闭") } },
                )
            }

            LaunchedEffect(state.toast) {
                state.toast?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    vm.consumeToast()
                }
            }

            JackTheme(state.settings.themeMode, state.settings.accent, state.settings.fontScale, state.settings.appIcon) {
                if (locked && state.settings.appLock) {
                    LockScreen(onUnlock = ::unlock)
                } else {
                val top = stack.lastOrNull()
                val lastDepth = remember { intArrayOf(stack.size) }
                val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                if (top != null) BackHandler(onBack = back)
                // 进一页：新页从右边滑进来；返回：往右滑出去。底下垫一层背景色，交替的那一下不透出窗口的白底
                val forward = stack.size >= lastDepth[0]
                androidx.compose.runtime.SideEffect { lastDepth[0] = stack.size }
                androidx.compose.animation.AnimatedContent(
                    targetState = top,
                    transitionSpec = { com.jack.ai.ui.pageTransition(forward) },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    label = "page",
                ) { screen ->
                    when (screen) {
                        null -> ChatScreen(
                            vm = vm,
                            state = state,
                            onOpenSettings = { stack.add(Screen.Settings) },
                            onOpenProject = { stack.add(Screen.ProjectPage(it)) },
                            onNewProject = { creatingProject = true },
                            onOpenMemory = { stack.add(Screen.Memory) },
                            onOpenSkills = { stack.add(Screen.Skills) },
                            onOpenRoutines = { stack.add(Screen.Routines) },
                            onOpenLibrary = { stack.add(Screen.Library) },
                            onOpenTables = { stack.add(Screen.Tables) },
                            onOpenReview = { stack.add(Screen.Review) },
                            onOpenApps = { stack.add(Screen.Apps) },
                            onOpenDownloads = { stack.add(Screen.Downloads) },
                            onOpenFiles = { stack.add(Screen.Files) },
                        )
                        Screen.Routines -> RoutinesScreen(vm = vm, state = state, onBack = back)
                        Screen.Library -> LibraryScreen(vm = vm, state = state, onBack = back)
                        Screen.Tables -> TablesScreen(onBack = back)
                        Screen.Review -> ReviewScreen(vm = vm, state = state, onBack = back)
                        Screen.Apps -> com.jack.ai.ui.AppsScreen(state = state, onBack = back)
                        Screen.Downloads -> com.jack.ai.ui.DownloadsScreen(onBack = back)
                        Screen.Files -> com.jack.ai.ui.FileLibraryScreen(state = state, onBack = back, onOpenChat = { id -> vm.select(id); stack.clear() })
                        Screen.Memory -> MemoryScreen(vm = vm, state = state, onBack = back)
                        Screen.Skills -> SkillsScreen(vm = vm, settings = state.settings, onBack = back)
                        Screen.Usage -> UsageScreen(vm = vm, state = state, onBack = back)
                        Screen.Settings -> SettingsScreen(
                            vm = vm,
                            settings = state.settings,
                            onBack = back,
                            onOpenSkills = { stack.add(Screen.Skills) },
                            onOpenUsage = { stack.add(Screen.Usage) },
                            canLock = ::canLock,
                        )
                        is Screen.ProjectPage -> ProjectScreen(
                            vm = vm,
                            state = state,
                            projectId = screen.id,
                            onBack = back,
                            onOpenChat = { stack.clear() },
                        )
                    }
                }
                // 工具执行前的确认（发邮件之类），在哪个页面都能弹出来
                state.confirm?.let { req ->
                    AlertDialog(
                        onDismissRequest = { vm.answerConfirm(req.id, false) },
                        title = { Text(req.title) },
                        text = {
                            Text(req.body, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        confirmButton = {
                            androidx.compose.foundation.layout.Row {
                                // 这一类事以后都不问（只认这一类，设置里能撤销）
                                req.always?.let { TextButton(onClick = { vm.answerAlways(req) }) { Text("以后都允许") } }
                                TextButton(onClick = { vm.answerConfirm(req.id, true) }) { Text(req.action) }
                            }
                        },
                        dismissButton = { TextButton(onClick = { vm.answerConfirm(req.id, false) }) { Text("取消") } },
                    )
                }
                if (creatingProject) {
                    RenameDialog("", "新建项目（起个名字）", onDismiss = { creatingProject = false }) { name ->
                        val p = Project(name = name)
                        vm.saveProject(p)
                        creatingProject = false
                        stack.add(Screen.ProjectPage(p.id))
                    }
                }
                }
            }
        }
    }

    companion object {
        /** Kavi 自己（浏览器）把自己的文件交给聊天时带上的暗号：每次启动随机生成，别的 App 不知道，见 [sharedFromOutside]。 */
        val INTERNAL_TOKEN: String = java.util.UUID.randomUUID().toString()
        const val EXTRA_INTERNAL = "com.jack.ai.INTERNAL"
        /** 最后一次在前台（已解锁）的时间，跨页面重建保留。 */
        /** 上次用 Kavi 的时间（聊天页和浏览器共用）：离开超过 30 秒再回来才上锁，两边来回切不锁。 */
        var lastActive = 0L
        /** App 现在是否在眼前（Work 任务做完时据此决定要不要发通知）。 */
        @Volatile var visible = false

        const val ACTION_NEW_CHAT = "com.jack.ai.NEW_CHAT"
        const val ACTION_VOICE = "com.jack.ai.VOICE"
        const val ACTION_RECORD = "com.jack.ai.RECORD"
        const val ACTION_RECORD_STOP = "com.jack.ai.RECORD_STOP"
        const val ACTION_NEW_WORK = "com.jack.ai.NEW_WORK"
        const val ACTION_CAMERA = "com.jack.ai.CAMERA"
        const val ACTION_DOWNLOADS = "com.jack.ai.DOWNLOADS"
        const val ACTION_OPEN_CHAT = "com.jack.ai.OPEN_CHAT"
    }
}
