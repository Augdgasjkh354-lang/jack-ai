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
    data object Review : Screen
    data class ProjectPage(val id: String) : Screen
}

class MainActivity : FragmentActivity() {
    private val vm: ChatViewModel by viewModels()

    /** 开了应用锁时，是否需要解锁。 */
    private var locked by mutableStateOf(false)
    private val stack = mutableStateListOf<Screen>()

    override fun onStart() {
        super.onStart()
        // 离开超过 30 秒再回来，重新上锁
        if (vm.state.value.settings.appLock && (lastActive == 0L || SystemClock.elapsedRealtime() - lastActive > 30_000)) {
            locked = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (!locked) lastActive = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // 后台做梦可能更新了记忆
        vm.reloadMemory()
        vm.reloadRoutines()
        vm.mergeInbox()
        if (locked) unlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_CHAT -> { stack.clear(); vm.newChat() }
            ACTION_OPEN_CHAT -> { stack.clear(); vm.mergeInbox(intent.getStringExtra("convId")) }
            ACTION_VOICE -> { stack.clear(); vm.newChat(); vm.requestVoice() }
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
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

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
                .setTitle("解锁 Jack AI")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
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

            JackTheme(state.settings.themeMode, state.settings.accent, state.settings.fontScale) {
                if (locked && state.settings.appLock) {
                    LockScreen(onUnlock = ::unlock)
                } else {
                val top = stack.lastOrNull()
                val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                if (top != null) BackHandler(onBack = back)
                when (top) {
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
                        onOpenReview = { stack.add(Screen.Review) },
                    )
                    Screen.Routines -> RoutinesScreen(vm = vm, state = state, onBack = back)
                    Screen.Library -> LibraryScreen(vm = vm, state = state, onBack = back)
                    Screen.Review -> ReviewScreen(vm = vm, state = state, onBack = back)
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
                        projectId = top.id,
                        onBack = back,
                        onOpenChat = { stack.clear() },
                    )
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
                        confirmButton = { TextButton(onClick = { vm.answerConfirm(req.id, true) }) { Text(req.action) } },
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
        /** 最后一次在前台（已解锁）的时间，跨页面重建保留。 */
        private var lastActive = 0L

        const val ACTION_NEW_CHAT = "com.jack.ai.NEW_CHAT"
        const val ACTION_VOICE = "com.jack.ai.VOICE"
        const val ACTION_OPEN_CHAT = "com.jack.ai.OPEN_CHAT"
    }
}
