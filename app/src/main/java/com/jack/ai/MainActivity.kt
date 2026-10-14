package com.jack.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jack.ai.data.Project
import com.jack.ai.ui.ChatScreen
import com.jack.ai.ui.JackTheme
import com.jack.ai.ui.ProjectScreen
import com.jack.ai.ui.RenameDialog
import com.jack.ai.ui.SettingsScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

sealed interface Screen {
    data object Settings : Screen
    data class ProjectPage(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            // 聊天页是底，其他页面叠在上面
            val stack = remember {
                mutableStateListOf<Screen>().apply { if (state.settings.apiKey.isBlank()) add(Screen.Settings) }
            }
            var creatingProject by remember { mutableStateOf(false) }

            LaunchedEffect(state.toast) {
                state.toast?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    vm.consumeToast()
                }
            }

            JackTheme(state.settings.themeMode) {
                val top = stack.lastOrNull()
                if (top != null) BackHandler { stack.removeAt(stack.lastIndex) }
                when (top) {
                    null -> ChatScreen(
                        vm = vm,
                        state = state,
                        onOpenSettings = { stack.add(Screen.Settings) },
                        onOpenProject = { stack.add(Screen.ProjectPage(it)) },
                        onNewProject = { creatingProject = true },
                    )
                    Screen.Settings -> SettingsScreen(
                        vm = vm,
                        settings = state.settings,
                        onBack = { stack.removeAt(stack.lastIndex) },
                    )
                    is Screen.ProjectPage -> ProjectScreen(
                        vm = vm,
                        state = state,
                        projectId = top.id,
                        onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                        onOpenChat = { stack.clear() },
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
