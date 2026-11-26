package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Flashcard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    var reviewing by remember { mutableStateOf<String?>(null) } // 正在复习的卡组，"*" 表示全部
    var deletingDeck by remember { mutableStateOf<String?>(null) }
    // 提前复习（卡组里还没到期）：开始时记下这组的卡，评过的不再出现
    var ahead by remember { mutableStateOf<Set<String>?>(null) }
    val done = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val now = System.currentTimeMillis()
    // 系统返回键：复习中先回到卡组列表，不直接退出整页
    androidx.activity.compose.BackHandler(enabled = reviewing != null) { reviewing = null; ahead = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reviewing == null) "复习卡片" else "复习中") },
                navigationIcon = {
                    IconButton(onClick = { if (reviewing != null) { reviewing = null; ahead = null } else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        val deck = reviewing
        if (deck != null) {
            val pick = ahead
            val due = if (pick != null) state.cards.filter { it.id in pick && it.id !in done }.sortedBy { it.due }
                else state.cards.filter { (deck == "*" || it.deck == deck) && it.due <= now }.sortedBy { it.due }
            Box(Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
                val card = due.firstOrNull()
                if (card == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎉", style = MaterialTheme.typography.displaySmall)
                        Text("这一轮复习完了", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.size(16.dp))
                        OutlinedButton(onClick = { reviewing = null; ahead = null }) { Text("返回") }
                    }
                } else {
                    FlashcardView(card, remaining = due.size) { grade -> done += card.id; vm.reviewCard(card.id, grade) }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val totalDue = state.cards.count { it.due <= now }
            item {
                if (totalDue > 0) {
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { ahead = null; reviewing = "*" }, modifier = Modifier.fillMaxWidth()) { Text("开始复习（$totalDue 张到期）") }
                }
            }
            if (state.cards.isEmpty()) {
                item { Text("还没有卡片。在聊天里说「把这段做成记忆卡片」，Kavi 会做好放到这里，按遗忘曲线提醒你复习。", color = MaterialTheme.colorScheme.outline) }
            }
            items(state.cards.groupBy { it.deck }.toList(), key = { it.first }) { (name, cards) ->
                val dueCount = cards.count { it.due <= now }
                // 没到期的卡组点进去也能提前过一遍，不再点了没反应
                Card(onClick = {
                    done.clear()
                    ahead = if (dueCount > 0) null else cards.map { it.id }.toSet()
                    reviewing = name
                }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${cards.size} 张 · " + if (dueCount > 0) "$dueCount 张该复习了" else "下次复习：" +
                                    friendlyDay(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cards.minOf { it.due }))) + " · 点一下提前复习",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dueCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deletingDeck = name }) { Icon(Icons.Default.Delete, "删除卡组") }
                    }
                }
            }
        }
    }
    deletingDeck?.let { d ->
        ConfirmDeleteDialog("删除卡组？", "「$d」里的卡片会全部删除。", onDismiss = { deletingDeck = null }) {
            vm.deleteDeck(d); deletingDeck = null
        }
    }
}

@Composable
private fun FlashcardView(card: Flashcard, remaining: Int, onGrade: (Int) -> Unit) {
    var shown by remember(card.id) { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // 翻牌：卡片绕竖轴转半圈，转过一半时换成背面（背面反着转回来，字是正的）
    val turn by androidx.compose.animation.core.animateFloatAsState(if (shown) 180f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 300f), label = "flip")
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    fun flip() { if (!shown) { haptics.tick(); shown = true } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val st = MaterialTheme.typography.labelMedium
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Text("${card.deck} · 还剩 ", style = st, color = ink)
            RollingText(remaining, st, color = ink)
            Text(" 张", style = st, color = ink)
        }
        Spacer(Modifier.size(12.dp))
        // 每张新卡淡入
        androidx.compose.runtime.key(card.id) {
            Card(onClick = { flip() }, modifier = Modifier.fillMaxWidth().weight(1f).appear(rise = 0)
                .graphicsLayer { rotationY = turn; cameraDistance = 14f * density }) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp).graphicsLayer { if (turn > 90f) rotationY = 180f },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (turn <= 90f) {
                        Text(card.front, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(24.dp))
                    } else {
                        Text(card.front, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 20.dp))
                        Text(card.back, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.heightIn(max = 320.dp))
                    }
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        androidx.compose.animation.AnimatedContent(shown, label = "grade",
            transitionSpec = { (fadeIn(Springs.defaultEffects()) + slideInVertically(Springs.defaultSpatial()) { it / 2 }) togetherWith fadeOut(Springs.fastEffects()) }) { open ->
            if (open) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { haptics.tick(); onGrade(0) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("忘了") }
                    Button(onClick = { haptics.tick(); onGrade(1) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Text("模糊") }
                    Button(onClick = { haptics.done(); onGrade(2) }, modifier = Modifier.weight(1f)) { Text("记得") }
                }
            } else {
                Button(onClick = { flip() }, modifier = Modifier.fillMaxWidth()) { Text("显示答案") }
            }
        }
    }
}
