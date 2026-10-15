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
import androidx.compose.material3.OutlinedButton
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
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Flashcard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addLibraryFiles(it) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资料库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "导入") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "把书、笔记、课件放进来（PDF、Word、文本、Markdown），直接问“那本书里关于…怎么说”。资料只存在手机里，提问时只发送相关段落。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !state.importing) { Text("导入文档") }
                    if (state.importing) {
                        Spacer(Modifier.size(12.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("正在读取…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.library.isEmpty()) {
                item { Text("资料库还是空的。", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp)) }
            }
            items(state.library.sortedByDescending { it.addedAt }, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.File, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${d.chars} 字 · ${fmt.format(Date(d.addedAt))} 加入", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = d.id }) { Icon(Icons.Default.Delete, "删除") }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        val name = state.library.firstOrNull { it.id == id }?.name.orEmpty()
        ConfirmDeleteDialog("从资料库删除？", "「$name」会从资料库移除。", onDismiss = { deleting = null }) {
            vm.deleteLibraryDoc(id); deleting = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    var reviewing by remember { mutableStateOf<String?>(null) } // 正在复习的卡组，"*" 表示全部
    var deletingDeck by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reviewing == null) "复习卡片" else "复习中") },
                navigationIcon = {
                    IconButton(onClick = { if (reviewing != null) reviewing = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        val deck = reviewing
        if (deck != null) {
            val due = state.cards.filter { (deck == "*" || it.deck == deck) && it.due <= now }.sortedBy { it.due }
            Box(Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
                val card = due.firstOrNull()
                if (card == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎉", style = MaterialTheme.typography.displaySmall)
                        Text("这一轮复习完了", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.size(16.dp))
                        OutlinedButton(onClick = { reviewing = null }) { Text("返回") }
                    }
                } else {
                    FlashcardView(card, remaining = due.size) { grade -> vm.reviewCard(card.id, grade) }
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
                Text(
                    "学完一个主题，AI 会把重点做成卡片。记得越牢，复习间隔越长。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalDue > 0) {
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { reviewing = "*" }, modifier = Modifier.fillMaxWidth()) { Text("开始复习（$totalDue 张到期）") }
                }
            }
            if (state.cards.isEmpty()) {
                item { Text("还没有卡片。跟 AI 说“教我…”，学完它会帮你做卡片。", color = MaterialTheme.colorScheme.outline) }
            }
            items(state.cards.groupBy { it.deck }.toList(), key = { it.first }) { (name, cards) ->
                val dueCount = cards.count { it.due <= now }
                Card(onClick = { if (dueCount > 0) reviewing = name }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${cards.size} 张 · " + if (dueCount > 0) "$dueCount 张该复习了" else "下次复习：" +
                                    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(cards.minOf { it.due })),
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
    Column(Modifier.fillMaxSize()) {
        Text("${card.deck} · 还剩 $remaining 张", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Card(onClick = { shown = true }, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(card.front, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                AnimatedVisibility(shown) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(Modifier.padding(vertical = 20.dp))
                        Text(card.back, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.heightIn(max = 320.dp))
                    }
                }
                if (!shown) {
                    Spacer(Modifier.size(24.dp))
                    Text("先想一想，再点一下看答案", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        if (shown) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onGrade(0) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("忘了") }
                Button(onClick = { onGrade(1) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Text("模糊") }
                Button(onClick = { onGrade(2) }, modifier = Modifier.weight(1f)) { Text("记得") }
            }
        } else {
            Button(onClick = { shown = true }, modifier = Modifier.fillMaxWidth()) { Text("显示答案") }
        }
    }
}
