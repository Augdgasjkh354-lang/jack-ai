package com.jack.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.PinnedPages

/**
 * 侧边栏「我的页面」点开的一页：小工具网页铺满，像 App 自带的页面一样。
 * 右上角：让 Kavi 改（回到做它的对话，或者开个新对话说要改它）、移出侧边栏、分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(vm: ChatViewModel, state: UiState, rel: String, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    val file = PinnedPages.file(ctx, rel)
    // 文件没了（对话删了之类）：退回去，侧边栏里那一项也不会再显示
    LaunchedEffect(file) { if (file == null) onBack() }
    if (file == null) return
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(PinnedPages.title(rel)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("让 Kavi 改") }, onClick = {
                                menu = false
                                // 做它的对话还在就回那里（有之前的来龙去脉）；不在就开个新对话，Kavi 按名字找得到它
                                val convId = file.parentFile?.name?.takeIf { k -> state.conversations.any { it.id == k } }
                                if (convId != null) { vm.select(convId); ChatFocus.prefill = "把「${PinnedPages.title(rel)}」改成：" }
                                else { vm.newChat(); ChatFocus.prefill = "把页面「@$rel」改成：" }
                                onOpenChat()
                            })
                            DropdownMenuItem(text = { Text("移出侧边栏") }, onClick = { menu = false; vm.togglePage(file); onBack() })
                            DropdownMenuItem(text = { Text("分享") }, onClick = {
                                menu = false; shareFile(ctx, com.jack.ai.data.GeneratedFile(file.name, file.absolutePath, "text/html"))
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        // 改过（Kavi 刚改完回来）就重新加载
        key(file.lastModified()) { AppWebView(file, Modifier.padding(padding).fillMaxSize(), inline = false) }
    }
}
