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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(table?.let { "德州扑克 · 第 ${it.game.hand} 手" } ?: "德州扑克") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { if (table != null) TextButton(onClick = { confirmEnd = true }) { Text("结束牌局") } },
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
private fun PokerBoard(t: PokerTable, modifier: Modifier) {
    val tick by t.tick.collectAsState()
    tick.hashCode()
    val g = t.game
    val me = g.seats.first { it.human }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 四个对手：两行两列
        g.seats.filter { !it.human }.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> SeatCard(t, s.id, Modifier.weight(1f)) }
            }
        }
        // 中间：公共牌和底池
        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 5).forEach { i -> g.board.getOrNull(i)?.let { CardFace(it, big = true) } ?: CardSlot() }
            }
            Spacer(Modifier.height(10.dp))
            Text(if (g.result == null) "底池 ${g.pot}" else g.result!!.text, style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, color = if (g.result != null) colors.primary else colors.onSurface)
            Spacer(Modifier.height(6.dp))
            // 最近几件事
            g.log.takeLast(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        // 你自己
        Row(verticalAlignment = Alignment.CenterVertically) {
            me.hole.forEach { CardFace(it, big = true); Spacer(Modifier.width(6.dp)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("你" + if (g.dealer == me.id) " · 庄" else "", style = MaterialTheme.typography.titleSmall)
                Text("筹码 ${me.chips}" + if (me.bet > 0) " · 本轮已下 ${me.bet}" else "", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (me.hole.isNotEmpty() && g.board.size >= 3) Text(HandEval.name(HandEval.best(me.hole + g.board)), style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.navigationBarsPadding().padding(bottom = 10.dp)) {
            when {
                g.over -> Text(if (me.out) "你的筹码输光了。点「结束牌局」重新开一局。" else "你赢下了整桌！点「结束牌局」再来一局。",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                g.result != null -> Button(onClick = { t.startHand() }, modifier = Modifier.fillMaxWidth()) { Text("下一手") }
                g.toAct == me.id -> ActionBar(t)
                else -> Text(if (t.thinking >= 0) "${g.seats[t.thinking].name} 在想……" else "等别人出牌……",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ActionBar(t: PokerTable) {
    val g = t.game
    val me = g.seats.first { it.human }
    val l = g.legal(me.id)
    var raising by remember(g.hand, g.street, g.log.size) { mutableStateOf(false) }
    var to by remember(g.hand, g.street, g.log.size) { mutableStateOf(l.minRaiseTo.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (raising && l.canRaise) {
            Text("加注到 ${to.toInt()}", style = MaterialTheme.typography.titleSmall)
            Slider(value = to, onValueChange = { to = it }, valueRange = l.minRaiseTo.toFloat()..l.maxRaiseTo.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 常用的几档：半池、一池
                listOf("½ 池" to g.pot / 2, "1 池" to g.pot).forEach { (label, add) ->
                    val v = (g.legal(me.id).toCall + me.bet + add).coerceIn(l.minRaiseTo, l.maxRaiseTo)
                    OutlinedButton(onClick = { to = v.toFloat() }) { Text(label) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { t.play(Act.Raise(to.toInt())) }) { Text("确定") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { t.play(Act.Fold) }, modifier = Modifier.weight(1f)) { Text("弃牌") }
            Button(onClick = { t.play(if (l.canCheck) Act.Check else Act.Call) }, modifier = Modifier.weight(1.2f)) {
                Text(if (l.canCheck) "过牌" else if (l.toCall >= me.chips) "全下 ${me.chips}" else "跟 ${l.toCall}")
            }
            if (l.canRaise) FilledTonalButton(onClick = { raising = !raising }, modifier = Modifier.weight(1f)) { Text(if (raising) "收起" else "加注") }
            else if (l.toCall < me.chips) FilledTonalButton(onClick = { t.play(Act.AllIn) }, modifier = Modifier.weight(1f)) { Text("全下") }
        }
    }
}

@Composable
private fun SeatCard(t: PokerTable, id: Int, modifier: Modifier) {
    val g = t.game
    val s = g.seats[id]
    val colors = MaterialTheme.colorScheme
    val acting = g.toAct == id || t.thinking == id
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (acting) colors.primaryContainer else colors.surfaceContainerLow,
        border = if (acting) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(10.dp).let { if (s.out || s.folded) it.then(Modifier) else it }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (g.dealer == id) {
                    Spacer(Modifier.width(4.dp))
                    Text("庄", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary,
                        modifier = Modifier.background(colors.primary, CircleShape).padding(horizontal = 5.dp))
                }
            }
            Text(t.modelName(id), style = MaterialTheme.typography.labelSmall, color = colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (s.out) "出局" else "筹码 ${s.chips}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val shown = g.result?.shown?.containsKey(id) == true
                if (s.hole.isNotEmpty() && !s.out && !s.folded) s.hole.forEach { c -> if (shown) CardFace(c) else CardBack(); Spacer(Modifier.width(3.dp)) }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        t.thinking == id -> "想……"
                        s.folded && !s.out -> "弃牌"
                        else -> s.last
                    },
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            t.says[id]?.let { say ->
                Text("「$say」", style = MaterialTheme.typography.labelSmall, color = colors.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun CardFace(c: PlayingCard, big: Boolean = false) {
    val w = if (big) 44.dp else 24.dp
    val h = if (big) 62.dp else 34.dp
    val red = Color(0xFFD32F2F)
    Column(
        Modifier.size(w, h).background(Color.White, RoundedCornerShape(if (big) 7.dp else 4.dp))
            .border(0.5.dp, Color(0x33000000), RoundedCornerShape(if (big) 7.dp else 4.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (c.red) red else Color(0xFF1B1B1F)
        Text(c.rankText, color = tint, fontWeight = FontWeight.Bold, fontSize = if (big) 18.sp else 11.sp, lineHeight = if (big) 20.sp else 12.sp)
        Text(c.suitText, color = tint, fontSize = if (big) 16.sp else 10.sp, lineHeight = if (big) 18.sp else 11.sp)
    }
}

@Composable
private fun CardBack() {
    Box(Modifier.size(24.dp, 34.dp).background(Color(0xFF3949AB), RoundedCornerShape(4.dp)).border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)))
}

@Composable
private fun CardSlot() {
    Box(Modifier.size(44.dp, 62.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)))
}

@Composable
private fun GameSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}
