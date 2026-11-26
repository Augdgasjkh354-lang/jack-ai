package com.jack.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Workspace
import com.jack.ai.data.backgroundModelKey
import com.jack.ai.data.modelChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 小工具网页里写了 <meta name="kavi-kind" content="game"> 的算游戏（Kavi 做游戏时会写上）。 */
internal fun isGameHtml(head: String): Boolean = com.jack.ai.data.games.GameFiles.isGame(head)

/** 侧边栏的「游戏」：和 Kavi 一起玩的牌桌，加上 Kavi 做过的小游戏；也能让 Kavi 现做一个。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val games by produceState(emptyList<File>(), refresh) {
        value = withContext(Dispatchers.IO) {
            val builtin = com.jack.ai.data.games.BuiltinGame.entries.map { it.convId }.toSet()
            Workspace.root(ctx).listFiles().orEmpty().filter { it.isDirectory && it.name !in builtin }
                .flatMap { dir -> Workspace.files(dir).filter { it.extension.equals("html", true) } }
                .filter { f -> runCatching { f.bufferedReader().use { r -> CharArray(4096).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("").let(::isGameHtml) }
                .sortedByDescending { it.lastModified() }
        }
    }
    var open by remember { mutableStateOf<File?>(null) }
    var asking by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<com.jack.ai.data.games.BuiltinGame?>(null) }
    /** 回到做这个游戏的对话，输入框里先填好开头，接着说要怎么改。 */
    fun editIn(convId: String, start: String) {
        vm.select(convId)
        ChatFocus.prefill = start
        onOpenChat()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { GameSection("和 Kavi 一起玩") }
            items(com.jack.ai.data.games.BuiltinGame.entries.toList(), key = { it.convId }) { game ->
                Card(onClick = { open = vm.ensureGame(game) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("♠", fontSize = 28.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(game.title, style = MaterialTheme.typography.titleMedium)
                            Text(game.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val gameFile = java.io.File(Workspace.dir(ctx, game.convId), game.title + ".html")
                        GameMenu(
                            onEdit = { vm.ensureGame(game); editIn(game.convId, "把${game.title}改成：") },
                            onReset = { resetting = game },
                            inDrawer = gameFile.exists() && com.jack.ai.data.PinnedPages.rel(ctx, gameFile) in state.settings.pinnedPages,
                            onTogglePage = { vm.togglePage(vm.ensureGame(game)) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); GameSection("Kavi 做的游戏") }
            item {
                FilledTonalButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { Text("让 Kavi 做个游戏") }
            }
            if (games.isEmpty()) item {
                Text("还没有。说说想玩什么，Kavi 做成能直接在手机上玩的小游戏，做好会出现在这里。",
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            }
            items(games, key = { it.absolutePath }) { f ->
                Card(onClick = { open = f }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name.removeSuffix(".html"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val convId = f.parentFile?.name?.takeIf { k -> state.conversations.any { it.id == k } }
                                convId?.let { id -> DropdownMenuItem(text = { Text("让 Kavi 改") }, onClick = { menu = false; editIn(id, "把「${f.name.removeSuffix(".html")}」改成：") }) }
                                val inDrawer = com.jack.ai.data.PinnedPages.rel(ctx, f) in state.settings.pinnedPages
                                DropdownMenuItem(text = { Text(if (inDrawer) "移出侧边栏" else "放进侧边栏") }, onClick = { menu = false; vm.togglePage(f) })
                                DropdownMenuItem(text = { Text("放到桌面") }, onClick = {
                                    menu = false
                                    if (!MiniAppActivity.pin(ctx, f)) android.widget.Toast.makeText(ctx, "这个桌面不支持放快捷方式", android.widget.Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text("分享") }, onClick = {
                                    menu = false; shareFile(ctx, com.jack.ai.data.GeneratedFile(f.name, f.absolutePath, "text/html"))
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    open?.let { f -> AppViewer(f) { open = null; refresh++ } }
    resetting?.let { game ->
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text("恢复原版？") },
            text = { Text("Kavi 对「${game.title}」做的修改和存的进度都会清掉，换回 App 自带的版本。对话记录还在。") },
            confirmButton = { TextButton(onClick = { vm.ensureGame(game, reset = true); resetting = null }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("取消") } },
        )
    }
    if (asking) {
        var idea by remember { mutableStateOf("") }
        val ideas = listOf("贪吃蛇", "2048", "五子棋，Kavi 当对手", "你画我猜，Kavi 来猜", "成语接龙", "打砖块")
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("想玩什么？") },
            text = {
                Column {
                    OutlinedTextField(idea, { idea = it }, placeholder = { Text("比如：五子棋，Kavi 当对手") }, modifier = Modifier.fillMaxWidth().focusOnShow())
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ideas.forEach { t -> androidx.compose.material3.SuggestionChip(onClick = { idea = t }, label = { Text(t) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = idea.isNotBlank(), onClick = {
                    asking = false
                    vm.newChat()
                    vm.send("帮我做一个能在手机上直接玩的小游戏：${idea.trim()}。")
                    onOpenChat()
                }) { Text("做") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun GameMenu(onEdit: () -> Unit, onReset: () -> Unit, inDrawer: Boolean, onTogglePage: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("让 Kavi 改") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(if (inDrawer) "移出侧边栏" else "放进侧边栏") }, onClick = { menu = false; onTogglePage() })
            DropdownMenuItem(text = { Text("恢复原版") }, onClick = { menu = false; onReset() })
        }
    }
}

@Composable
private fun GameSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}
