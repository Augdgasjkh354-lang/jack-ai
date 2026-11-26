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
import com.jack.ai.data.games.Act
import com.jack.ai.data.games.Card as PlayingCard
import com.jack.ai.data.games.HandEval
import com.jack.ai.data.games.Opponent
import com.jack.ai.data.games.PokerTable
import com.jack.ai.data.modelChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 小工具网页里写了 <meta name="kavi-kind" content="game"> 的算游戏（Kavi 做游戏时会写上）。 */
internal fun isGameHtml(head: String): Boolean =
    Regex("""<meta[^>]+name=["']kavi-kind["'][^>]+content=["']game["']""", RegexOption.IGNORE_CASE).containsMatchIn(head) ||
        Regex("""<meta[^>]+content=["']game["'][^>]+name=["']kavi-kind["']""", RegexOption.IGNORE_CASE).containsMatchIn(head)

/** 侧边栏的「游戏」：和 Kavi 一起玩的牌桌，加上 Kavi 做过的小游戏；也能让 Kavi 现做一个。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit, onOpenPoker: () -> Unit, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val games by produceState(emptyList<File>(), refresh) {
        value = withContext(Dispatchers.IO) {
            Workspace.root(ctx).listFiles().orEmpty().filter { it.isDirectory }
                .flatMap { dir -> Workspace.files(dir).filter { it.extension.equals("html", true) } }
                .filter { f -> runCatching { f.bufferedReader().use { r -> CharArray(4096).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("").let(::isGameHtml) }
                .sortedByDescending { it.lastModified() }
        }
    }
    var open by remember { mutableStateOf<File?>(null) }
    var asking by remember { mutableStateOf(false) }
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
            item {
                val running = PokerTable.current?.takeIf { !it.game.over }
                Card(onClick = onOpenPoker, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("♠", fontSize = 28.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                        Column(Modifier.weight(1f)) {
                            Text("德州扑克", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (running != null) "牌局进行中 · 第 ${running.game.hand} 手，点一下接着打"
                                else "你和 4 个对手，每个对手可以用不同的模型，各想各的",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

/** 德州扑克：没开局时先摆对手（名字、打法、用哪个模型），开了就是牌桌。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokerScreen(state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var table by remember { mutableStateOf(PokerTable.current) }
    var confirmEnd by remember { mutableStateOf(false) }
    // 标题上的「第几手」跟着牌桌变
    table?.tick?.collectAsState()?.value
    // 牌桌一律是深色的桌布配色（设置也一样），不跟主题走
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Felt.Gold, onPrimary = Color(0xFF1B1300), secondaryContainer = Color(0xFF2B3A33), onSecondaryContainer = Color.White,
        surface = Color(0xFF12201A), surfaceContainerLow = Color(0xFF16261F), surfaceContainerHigh = Color(0xFF1C2E26),
        onSurface = Color.White, onSurfaceVariant = Felt.Dim, outline = Color.White.copy(alpha = 0.4f), outlineVariant = Color.White.copy(alpha = 0.16f),
    )) {
        Box(Modifier.fillMaxSize().background(Felt.Backdrop)) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                            titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
                        title = {
                            Column {
                                Text("德州扑克", style = MaterialTheme.typography.titleMedium)
                                table?.let { Text("第 ${it.game.hand} 手 · 盲注 ${it.game.small}/${it.game.big}", style = MaterialTheme.typography.labelSmall, color = Felt.Dim) }
                            }
                        },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                        actions = { if (table != null) TextButton(onClick = { confirmEnd = true }) { Text("结束牌局", color = Felt.Dim) } },
                    )
                },
            ) { padding ->
                val t = table
                if (t == null) PokerSetup(state, Modifier.padding(padding)) { opps ->
                    val nt = PokerTable(ctx.applicationContext, state.settings, opps)
                    PokerTable.current = nt
                    table = nt
                    nt.startHand()
                } else PokerBoard(t, Modifier.padding(padding))
            }
        }
    }
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("结束这局？") },
        text = { Text("筹码清零，下次重新摆对手开局。") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; table?.close(); PokerTable.current = null; table = null }) { Text("结束") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("接着打") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PokerSetup(state: UiState, modifier: Modifier, onStart: (List<Opponent>) -> Unit) {
    val choices = state.settings.modelChoices()
    // 默认都用后台模型（便宜）；没有能用的模型就是规则机器人
    val def = state.settings.backgroundModelKey().orEmpty()
    val opps = remember {
        mutableStateListOf<Opponent>().apply { PokerTable.NAMES.forEachIndexed { i, n -> add(Opponent(n, PokerTable.STYLES[i], def)) } }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("4 个对手各用自己的模型、各记各的牌局，谁也看不到别人的手牌。模型出错时按规则机器人的走法出牌，牌局不会卡住。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        opps.forEachIndexed { i, o ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(o.name, { v -> opps[i] = o.copy(name = v.take(6)) }, singleLine = true,
                            modifier = Modifier.width(120.dp), textStyle = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(10.dp))
                        Text(o.style, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = o.model.isBlank(), onClick = { opps[i] = o.copy(model = "") }, label = { Text("规则机器人（不花钱）") })
                        choices.forEach { c ->
                            FilterChip(selected = o.model == c.key, onClick = { opps[i] = o.copy(model = c.key) }, label = { Text(c.label, maxLines = 1) })
                        }
                    }
                }
            }
        }
        Text("每人 1000 筹码，盲注 10/20。每次轮到对手只把新发生的事发给它，花费很小；用量里记在「游戏 · 德州扑克」。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Button(onClick = { onStart(opps.map { it.copy(name = it.name.trim().ifBlank { "对手" }) }) }, modifier = Modifier.fillMaxWidth()) { Text("开局") }
    }
}

@Composable
private fun GameSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}
