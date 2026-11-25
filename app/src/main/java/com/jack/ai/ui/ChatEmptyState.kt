package com.jack.ai.ui

import com.jack.ai.api.webReady
import androidx.compose.foundation.border
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.collectAsState
import com.jack.ai.data.activeSkills
import com.jack.ai.data.readyMails
import com.jack.ai.data.ChatExport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.Conversation
import com.jack.ai.data.Settings
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.endpoint
import com.jack.ai.data.modelChoices
import com.jack.ai.data.modelLabel
import com.jack.ai.data.nextAt
import kotlinx.coroutines.launch
import java.util.Calendar

// 新对话的空白页：问候、今日概览、建议入口（从 ChatScreen.kt 拆出来）。

/** 空白页那几行入口各用什么图标。 */
private fun startIcon(key: String): ImageVector = when (key) {
    "xhs" -> AppIcons.Image
    "intro" -> AppIcons.File
    else -> AppIcons.Chat
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/** 问候下面那一行：今天还有几个提醒、任务，几个监控在盯着；什么都没有就是 null。 */
internal fun todayLine(routines: List<com.jack.ai.data.Routine>, now: Long = System.currentTimeMillis()): String? {
    val end = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis
    val today = routines.filter { it.kind != "watch" }.count { r -> r.nextAt(now)?.let { it <= end } == true }
    val watching = routines.count { it.kind == "watch" && it.enabled && it.until > now }
    val parts = listOfNotNull("$today 个提醒和任务".takeIf { today > 0 }, "$watching 个监控在盯着".takeIf { watching > 0 })
    return if (parts.isEmpty()) null else "今天还有 " + parts.joinToString("，")
}

@Composable
internal fun EmptyState(
    vm: ChatViewModel,
    state: UiState,
    conv: Conversation?,
    onOpenMemory: () -> Unit,
    onOpenRoutines: () -> Unit = {},
) {
    val project = state.project(state.currentProjectId)
    val colors = MaterialTheme.colorScheme
    // 新对话的空白页轻轻浮上来，不是一下子蹦出来
    val still = reduceMotion()
    val enter = remember(conv?.id) { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    LaunchedEffect(conv?.id) { enter.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24.dp.toPx() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 靠左的一句大问候，下面一行小字；入口是不带框的几行，沉在底部、离输入框近，单手好点
        Spacer(Modifier.height(56.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        // 「今天还有 2 个提醒」点一下就去看是哪两个
        val today = if (project == null) todayLine(state.routines) else null
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？"
            else today?.let { "$it ›" } ?: "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = (if (today != null) Modifier.clip(Radii.s).clickable(onClickLabel = "看提醒和任务", onClick = onOpenRoutines) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        // 写文案这些：点一下直接发出去，Kavi 用问题卡片把要知道的问清楚
        if (state.settings.bizStarts) {
            // 一行接一行地浮上来（换一个新对话重新排一次）
            androidx.compose.runtime.key(conv?.id) {
                com.jack.ai.data.BizStarts.ALL.forEachIndexed { i, b ->
                    Box(Modifier.appear(order = i + 2)) { SuggestionRow(startIcon(b.key), Hue.of(b.tint).color, b.title) { vm.send(b.prompt) } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 空白页的一行建议：一个彩色图标加一行字，不带框；按下去微微缩一下。 */
@Composable
private fun SuggestionRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.98f).clip(Radii.m)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { haptics.tick(); onClick() }
            .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

