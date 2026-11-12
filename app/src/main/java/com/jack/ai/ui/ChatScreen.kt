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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    state: UiState,
    onOpenSettings: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDownloads: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conv = state.current
    val project = state.project(state.currentProjectId)
    val ctx = LocalContext.current
    // 每个对话各有各的草稿：换到别的对话时把没发的字先收起来，回来再放回去，不会把 A 里写了一半的话发到 B 里。
    // 草稿存在手机上，App 被系统清掉再打开也还在。
    // 在这里（组合时）就换掉，比分享、语音塞进来的文字先一步，后者会接在换好的草稿后面
    val drafts = remember { DraftStore(ctx) }
    var text by rememberSaveable { mutableStateOf(drafts.get(conv?.id)) }
    var draftOf by remember { mutableStateOf(conv?.id) }
    // 在输入框里改一条发过的消息：editing 是那条消息，editBackup 是改之前输入框里本来的草稿
    var editing by remember { mutableStateOf<com.jack.ai.data.ChatMessage?>(null) }
    var editBackup by remember { mutableStateOf("") }
    fun stopEditing() { text = editBackup; editing = null }
    if (editing != null && (draftOf != conv?.id || conv?.messages?.none { it.id == editing?.id } != false)) stopEditing()
    if (draftOf != conv?.id) {
        drafts.put(draftOf, text)
        text = drafts.get(conv?.id)
        draftOf = conv?.id
    }
    // 打字时停一下再存，不每个字都写一次
    LaunchedEffect(Unit) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { Triple(draftOf, text, editing) }.debounce(400).collect { (id, t, e) -> if (e == null) drafts.put(id, t) }
    }
    // 点了建议、模板：光标放进输入框、弹出键盘，接着打字就行
    var focusInput by remember { mutableIntStateOf(0) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    // 删了消息：底下出一条「撤销」，不再先弹确认框
    fun onDeleted(n: Int) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("删除了 $n 条消息", actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed && !vm.undoDelete()) {
                Toast.makeText(ctx, "对话已经往下走了，撤销不了", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var showFiles by remember { mutableStateOf(false) }
    var memoryOf by remember { mutableStateOf<Conversation?>(null) }
    val bg = MaterialTheme.colorScheme.background
    // 眼前这个对话答完了：轻轻震一下（换对话引起的变化不算）
    val haptics = rememberHaptics()
    var wasStreaming by remember { mutableStateOf(conv?.id to state.streamingHere) }
    LaunchedEffect(conv?.id, state.streamingHere) {
        val (id, on) = wasStreaming
        if (id == conv?.id && on && !state.streamingHere) haptics.done()
        wasStreaming = conv?.id to state.streamingHere
    }
    // 分享进来或语音识别的文字，接到输入框后面
    LaunchedEffect(state.incomingText) {
        state.incomingText?.let { t ->
            text = if (text.isBlank()) t else text.trimEnd() + " " + t
            vm.consumeIncomingText()
        }
    }

    // 侧边栏开着时按返回键先收起侧边栏
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 关着时由 swipeToOpen 负责打开：抽屉自带的拖动碰到消息气泡会失灵，两个一起开还会互相拉扯（刚打开又被拉回去）。
        // 开着时交还给抽屉，往左划关上
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HistoryDrawer(
                state = state,
                onNew = { vm.newChat(); scope.launch { drawerState.close() } },
                onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                onDelete = vm::delete,
                onRename = vm::rename,
                onPin = vm::togglePin,
                onMemory = { memoryOf = it },
                onOpenProject = { scope.launch { drawerState.close() }; onOpenProject(it) },
                onNewProject = { scope.launch { drawerState.close() }; onNewProject() },
                onOpenMemory = { scope.launch { drawerState.close() }; onOpenMemory() },
                onOpenSkills = { scope.launch { drawerState.close() }; onOpenSkills() },
                onOpenRoutines = { scope.launch { drawerState.close() }; onOpenRoutines() },
                onOpenLibrary = { scope.launch { drawerState.close() }; onOpenLibrary() },
                onOpenTables = { scope.launch { drawerState.close() }; onOpenTables() },
                onOpenReview = { scope.launch { drawerState.close() }; onOpenReview() },
                onOpenApps = { scope.launch { drawerState.close() }; onOpenApps() },
                onOpenDownloads = { scope.launch { drawerState.close() }; onOpenDownloads() },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            containerColor = bg,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            topBar = {
                Column {
                // 顶栏透明：标题那一段背景几乎不透（滚上来的消息不和标题、模型名叠在一起看不清），只在最下面一小段淡出到全透明
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                    ),
                    // 空白页没有消息会滚上来，顶栏全透明，让那一角天露出来
                    modifier = if (conv == null || conv.messages.isEmpty()) Modifier
                        else Modifier.background(Brush.verticalGradient(0f to bg, 0.8f to bg.copy(alpha = 0.96f), 1f to bg.copy(alpha = 0f))),
                    title = {
                        // 中间一个胶囊：对话标题，下面是模型名，点一下换模型
                        ModelPicker(conv?.takeIf { it.messages.isNotEmpty() }?.title ?: "Kavi", state.settings, project?.name) { m -> vm.updateSettings { it.copy(model = m) } }
                    },
                    navigationIcon = {
                        CircleIconButton(AppIcons.Menu, "菜单", Modifier.padding(start = 12.dp)) { scope.launch { drawerState.open() } }
                    },
                    actions = {
                        Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircleIconButton(AppIcons.NewChat, "新对话") { vm.newChat(state.currentProjectId) }
                            // 文件夹收在「更多」里（做出了文件时，「更多」上标个数），顶栏右边只留两个圆按钮，标题胶囊才居中
                            if (conv != null && conv.messages.isNotEmpty()) ExportMenu(vm,
                                made = conv.messages.flatMap { it.files }.distinctBy { it.path }.size,
                                onFiles = { showFiles = true }, onMemory = { memoryOf = conv })
                        }
                    },
                )
                // 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。
                conv?.let { GoalBar(vm, it) }
                }
            },
            bottomBar = {
                // Kavi 结束了这个对话：输入框换成一句说明和「新对话」
                if (conv != null && conv.closedReason.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kavi 结束了这个对话：${conv.closedReason}", style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.OutlinedButton(onClick = { vm.newChat(state.currentProjectId) }) { Text("开一个新对话") }
                    }
                } else InputBar(
                    vm = vm,
                    state = state,
                    text = text,
                    onTextChange = { text = it },
                    focusRequest = focusInput,
                    editing = editing,
                    onCancelEdit = { stopEditing() },
                    onSubmitEdit = { editing?.let { m -> vm.editMessage(m.id, text) }; stopEditing() },
                    onNeedTavily = {
                        Toast.makeText(ctx, "请先在 设置 → 连接 → 联网搜索 里填 Exa 或 Tavily 的 Key", Toast.LENGTH_LONG).show()
                        onOpenSettings()
                    },
                )
            },
        ) { padding ->
            // 消息铺满整屏，从上下两个半透明的框后面滚过去
            Box(Modifier.fillMaxSize().swipeToOpen { scope.launch { drawerState.open() } }) {
                // Kavi 在用浏览器、回答里的实时画面又不在屏幕上时，网页窗口的落点（看不见，垫在消息下面、顶栏后面）
                BrowserHiddenHost(Modifier.align(Alignment.TopStart).padding(top = padding.calculateTopPadding() / 2), working = state.streaming, convId = conv?.id.orEmpty())
                if (conv == null || conv.messages.isEmpty()) {
                    // 和记忆页同一片天：右上角一轮跟着钟点变色的光，深色模式下有星星
                    SkyBackdrop(Modifier.fillMaxWidth().height(320.dp), key = conv?.id)
                    Box(Modifier.padding(padding)) { EmptyState(vm, state, conv, onOpenMemory, onSuggest = { text = it; focusInput++ }) }
                } else {
                    MessageList(vm, conv, state.streamingHere, padding, editingId = editing?.id, onDeleted = ::onDeleted, summaryCollapsed = state.settings.summaryCollapsed, onEdit = { m ->
                        if (editing == null) editBackup = text
                        editing = m
                        text = m.content
                        focusInput++
                    })
                }
            }
        }
    }
    memoryOf?.let { c -> ConversationMemoryDialog(vm, c) { memoryOf = null } }
    if (showFiles && conv != null) {
        FilesSheet(vm, conv, inProject = conv.projectId != null, onDismiss = { showFiles = false })
    }
}

/**
 * 在聊天内容上往右滑就打开侧边栏，和常见的聊天 App 一样。
 * 抽屉自带的拖动手势碰到消息气泡就失灵（气泡是 TextView，会把触摸吃掉），所以在父层先看一眼手指的走向：
 * 明显是横着往右划才打开，竖着滑照常滚动消息。
 */
internal fun Modifier.swipeToOpen(open: () -> Unit): Modifier = pointerInput(Unit) {
    val distance = 40.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 这里比网页先收到按下：先清掉上一次可能没清的标记，按在小工具上的话网页马上会再设上
        AppTouch.active = false
        var dx = 0f
        var dy = 0f
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            // 先竖着动了、或者往左划：这次不是开侧边栏；手指在小工具上时是在拖滑块
            if (AppTouch.active) break
            if (abs(dy) > slop && abs(dy) > abs(dx)) break
            if (dx < -slop) break
            if (dx > distance && dx > abs(dy) * 2) {
                change.consume()
                open()
                break
            }
        }
    }
}

@Composable
private fun ExportMenu(vm: ChatViewModel, made: Int, onFiles: () -> Unit, onMemory: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val busy by vm.exporting.collectAsState()
    val ctx = LocalContext.current
    Box {
        if (busy) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        else if (made > 0) androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("$made") } }) {
            CircleIconButton(Icons.Default.MoreVert, "更多") { open = true }
        } else CircleIconButton(Icons.Default.MoreVert, "更多") { open = true }
        RoundMenu(expanded = open, onDismiss = { open = false }) {
            MenuRow("文件夹", icon = AppIcons.Folder, sub = if (made > 0) "做出了 $made 个文件" else null) { open = false; onFiles() }
            MenuRow("记忆", icon = AppIcons.Moon) { open = false; onMemory() }
            listOf(
                ChatExport.Format.IMAGE to "分享为长图",
                ChatExport.Format.PDF to "导出为 PDF",
                ChatExport.Format.MARKDOWN to "导出为 Markdown",
            ).forEach { (fmt, label) ->
                MenuRow(label, icon = AppIcons.Share) {
                    open = false
                    vm.exportChat(fmt) { file -> shareExport(ctx, file, fmt.mime) }
                }
            }
        }
    }
}

private fun shareExport(ctx: android.content.Context, file: java.io.File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, file.name))
}

@Composable
private fun ModelPicker(title: String, settings: Settings, projectName: String?, onPick: (String) -> Unit) {
    val model = settings.model
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pressScale(press, 0.95f).heightIn(min = 42.dp).clip(CircleShape).background(raisedColor).hairline(CircleShape)
                .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { open = true }
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.widthIn(max = 150.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (projectName?.let { "$it · " } ?: "") + modelLabel(model),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 136.dp),
                )
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            }
        }
        RoundMenu(expanded = open, onDismiss = { open = false }, modifier = Modifier.heightIn(max = 460.dp)) {
            val choices = settings.modelChoices()
            val groups = choices.map { it.group }.distinct()
            choices.forEachIndexed { i, c ->
                // 有多个服务时，每组前面标一下服务名
                if (groups.size > 1 && (i == 0 || choices[i - 1].group != c.group)) {
                    Text(c.group, style = MaterialTheme.typography.labelMedium, color = colors.outline,
                        modifier = Modifier.padding(start = 20.dp, top = if (i > 0) 14.dp else 8.dp, bottom = 2.dp))
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c.key); open = false }.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = if (c.key == model) colors.primary else colors.onSurface,
                        fontWeight = if (c.key == model) FontWeight.SemiBold else null)
                    if (c.key == model) { Spacer(Modifier.width(12.dp)); Icon(Icons.Default.Check, "当前", Modifier.size(20.dp), tint = colors.primary) }
                }
            }
        }
    }
}

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
private fun EmptyState(
    vm: ChatViewModel,
    state: UiState,
    conv: Conversation?,
    onOpenMemory: () -> Unit,
    onSuggest: (String) -> Unit,
) {
    val project = state.project(state.currentProjectId)
    val colors = MaterialTheme.colorScheme
    // 新对话的空白页轻轻浮上来，不是一下子蹦出来
    val enter = remember(conv?.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(conv?.id) { enter.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24.dp.toPx() }
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 靠左的一句大问候，下面一行小字；建议是不带框的几行，留白多一些
        Spacer(Modifier.height(56.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？"
            else todayLine(state.routines) ?: "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(28.dp))
        // 写文案、回客户这些：点一下直接发出去，Kavi 用问题卡片把要知道的问清楚。不单独起标题，接着上面的卡片排
        if (state.settings.bizStarts) {
            com.jack.ai.data.BizStarts.ALL.forEach { b -> SuggestionRow(startIcon(b.key), Hue.of(b.tint).color, b.title) { vm.send(b.prompt) } }
        }
        // 常做的任务模板：默认收着，只留一行小字，点开才展开（平时用不上，不占地方）
        var showTemplates by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 12.dp).clip(Radii.s).clickable { showTemplates = !showTemplates }.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("常做的任务", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(2.dp))
            Chevron(showTemplates, Modifier.size(18.dp))
        }
        androidx.compose.animation.AnimatedVisibility(showTemplates) {
            Column(Modifier.padding(top = 6.dp)) { WorkTemplates(vm, state.settings.workTemplates, onSuggest) }
        }
    }
}

/** 空白页的一行建议：一个彩色图标加一行字，不带框；按下去微微缩一下。 */
@Composable
private fun SuggestionRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.98f).clip(Radii.m)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 空白页的一张建议卡：左边一个浅色底的彩色小图标，右边是标题。矮矮的一行，一屏能多放几张。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SuggestionCard(icon: ImageVector?, tint: Color, title: String, modifier: Modifier = Modifier, muted: Boolean = false,
                           onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = Radii.l
    // 按下去时卡片微微缩一下，手感更实
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, 0.97f)
            .height(64.dp)
            .clip(shape)
            .background(raisedColor)
            .hairline(shape)
            .combinedClickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(Radii.s).background(tint.copy(alpha = if (isDarkSurface) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = tint)
            else Text("＋", color = tint, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (muted) null else FontWeight.SemiBold,
            color = if (muted) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** 圆角小按钮：选中时浅陶土底色，没选中时只有细边框。 */
@Composable
private fun Pill(
    label: String,
    active: Boolean,
    icon: ImageVector? = null,
    dropdown: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    // 开关时底色、字色、边框渐变过去
    val spec = androidx.compose.animation.core.tween<Color>(Motion.NORMAL)
    val fill by androidx.compose.animation.animateColorAsState(if (active) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f), spec, label = "pill")
    val ink by androidx.compose.animation.animateColorAsState(if (active) colors.onPrimaryContainer else colors.onSurfaceVariant, spec, label = "pillInk")
    val edge by androidx.compose.animation.animateColorAsState(if (active) colors.outlineVariant.copy(alpha = 0f) else colors.outlineVariant, spec, label = "pillEdge")
    Surface(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = fill,
        contentColor = ink,
        border = BorderStroke(1.dp, edge),
    ) {
        Row(
            Modifier.padding(start = if (icon != null) 10.dp else 14.dp, end = if (dropdown) 6.dp else 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.size(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (dropdown) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, conv: Conversation, streaming: Boolean, bars: PaddingValues,
                        editingId: String?, onDeleted: (Int) -> Unit, onEdit: (com.jack.ai.data.ChatMessage) -> Unit,
                        summaryCollapsed: Boolean = false) {
    // 一打开就停在最后一条附近，不先闪一下最顶上
    val ctx = LocalContext.current
    val listState = remember(conv.id) { androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = (conv.messages.size - 1).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    val lastId = conv.messages.last().id
    val lastUserId = conv.messages.lastOrNull { it.role == "user" }?.id
    val follow = rememberFollowBottom(listState, conv.id, lastUserId, moving = streaming || StreamReveal.pending)
    // 往上翻看旧消息时收起键盘，屏幕留给消息（和微信一样）
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val hideKeyboard = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 4f) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(follow.connection).nestedScroll(hideKeyboard),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = bars.calculateTopPadding() + 12.dp, bottom = bars.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            conv.messages.forEach { msg ->
                item(key = msg.id) {
                    Box(Modifier.animateItem()) {
                        if (msg.role == "user") {
                            UserBubble(msg, onDelete = { vm.deleteFrom(msg.id).let { if (it > 0) onDeleted(it) } }, onEdit = { onEdit(msg) },
                                editing = msg.id == editingId)
                        } else {
                            val isLast = msg.id == lastId
                            AssistantMessage(
                                convId = conv.id,
                                msg = msg,
                                streaming = streaming && isLast,
                                isLast = isLast,
                                onRegenerate = vm::regenerate,
                                onDelete = { vm.deleteFrom(msg.id).let { if (it > 0) onDeleted(it) } },
                                onReview = vm::reviewLast,
                                onContinue = if (isLast && !msg.truncated && vm.canContinue(msg)) ({ vm.continueWork() }) else null,
                                onContinueWriting = vm::continueWriting,
                                onReport = { note -> vm.reportIssue(msg.id, note) },
                                onFixApp = { text -> vm.send(text) },
                                onAnswer = { text -> vm.send(text) },
                                // 列了清单的（在做任务）工具步骤按时间线显示
                                work = msg.plan.isNotEmpty(),
                                stepsCollapsed = summaryCollapsed,
                                onRestyle = if (msg.plan.isNotEmpty()) null else { edited ->
                                    if (streaming) Toast.makeText(ctx, "等这条答完再交给 Kavi", Toast.LENGTH_SHORT).show()
                                    else vm.send(restyleRequest(msg.content, edited))
                                },
                            )
                        }
                    }
                }
                // 已压缩的分界线放在被压缩的最后一条下面
                if (msg.id == conv.summaryUntilId) {
                    item(key = "summary-divider") { SummaryDivider(conv.summary) }
                }
            }
        }
        // 翻上去了：给一个回到底部的按钮
        androidx.compose.animation.AnimatedVisibility(
            visible = !follow.on && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding() + 12.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.7f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.7f),
        ) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { follow.on = true; scope.launch { val n = listState.layoutInfo.totalItemsCount; if (n > 0) listState.animateScrollToItem(n - 1, Int.MAX_VALUE) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Default.KeyboardArrowDown, "回到底部") }
        }
    }
}

@Composable
private fun SummaryDivider(summary: String) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  已压缩 · 查看提要  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("前情提要") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownText(summary, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant,
                        textSizeSp = 14f)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
        )
    }
}

private fun startVoiceIntent(ctx: android.content.Context, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
    try {
        launcher.launch(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "手机没有语音识别服务，可以用输入法自带的语音输入", Toast.LENGTH_LONG).show()
    }
}

/** 正在录音时输入框上面的一条：时长、声音大小，暂停 / 继续、停止并整理、放弃。 */
@Composable
private fun RecordBar(session: com.jack.ai.RecordService.Session) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var level by remember { mutableStateOf(0f) }
    var confirmCancel by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            // 声音大小：慢慢落下，看着不闪
            level = maxOf(com.jack.ai.RecordService.level(), level * 0.7f)
            kotlinx.coroutines.delay(150)
        }
    }
    val paused = session.pausedAt != null
    Surface(shape = Radii.l, color = colors.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Box(Modifier.size(10.dp).background(if (paused) colors.outline else colors.error, CircleShape))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text((if (paused) "暂停 " else "录音 ") + com.jack.ai.data.AudioPcm.stamp(session.elapsed(now) / 1000),
                    style = MaterialTheme.typography.titleSmall.tabular, color = colors.onErrorContainer)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.8f).height(3.dp).background(colors.onErrorContainer.copy(alpha = 0.15f), CircleShape)) {
                    Box(Modifier.fillMaxWidth(if (paused) 0f else level.coerceIn(0.02f, 1f)).height(3.dp).background(colors.onErrorContainer, CircleShape))
                }
            }
            TextButton(onClick = { com.jack.ai.RecordService.pause(ctx) }) { Text(if (paused) "继续" else "暂停") }
            TextButton(onClick = { com.jack.ai.RecordService.stop(ctx) }) { Text("停止并整理") }
            IconButton(onClick = { confirmCancel = true }) { Icon(Icons.Default.Close, "放弃") }
        }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("放弃这段录音？") },
        text = { Text("录到的会删掉，不会交给 Kavi。") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; com.jack.ai.RecordService.cancel(ctx) }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("接着录") } },
    )
}

@Composable
private fun InputBar(
    vm: ChatViewModel,
    state: UiState,
    text: String,
    onTextChange: (String) -> Unit,
    onNeedTavily: () -> Unit,
    focusRequest: Int = 0,
    editing: com.jack.ai.data.ChatMessage? = null,
    onCancelEdit: () -> Unit = {},
    onSubmitEdit: () -> Unit = {},
) {
    var attachMenu by remember { mutableStateOf(false) }
    // 「@技能」「@邮箱」选择框：null 不显示
    var mentionPick by remember { mutableStateOf<List<String>?>(null) }
    var thinkMenu by remember { mutableStateOf(false) }
    val settings = state.settings
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val dark = colors.background.red < 0.5f

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        vm.addImages(it)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.addFiles(it)
    }
    val ctx = LocalContext.current
    // 拍照：相机 App 把照片写进缓存目录里的这个文件
    var photoUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        photoUri?.let { if (ok) vm.addImages(listOf(it)) }
    }
    fun startCameraInner() {
        val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        photoUri = uri
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "手机上没有相机 App", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "打不开相机：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    fun startCamera() = try { startCameraInner() } catch (e: Exception) {
        Toast.makeText(ctx, "打不开相机：${e.message}", Toast.LENGTH_LONG).show()
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.receiveText(it) }
    }
    LaunchedEffect(state.cameraRequest) {
        if (state.cameraRequest) {
            vm.consumeCameraRequest()
            startCamera()
        }
    }
    LaunchedEffect(state.voiceRequest) {
        if (state.voiceRequest) {
            vm.consumeVoiceRequest()
            startVoiceIntent(ctx, speech)
        }
    }
    fun startVoice() = startVoiceIntent(ctx, speech)
    // 录音纪要：先要麦克风权限，再开录（录在前台服务里）
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) com.jack.ai.RecordService.start(ctx) else Toast.makeText(ctx, "没有麦克风权限，录不了音。可以在系统设置里给 Kavi 打开", Toast.LENGTH_LONG).show()
    }
    fun startRecord() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            com.jack.ai.RecordService.start(ctx)
        } else runCatching { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(state.recordRequest) {
        if (state.recordRequest) {
            vm.consumeRecordRequest()
            startRecord()
        }
    }
    // 录完（在这里点停、或在通知栏点「停止并整理」）：交给 Kavi
    val recorded = com.jack.ai.RecordService.done
    LaunchedEffect(recorded) {
        if (recorded != null) {
            com.jack.ai.RecordService.done = null
            vm.recordingDone(recorded.file, recorded.seconds)
        }
    }
    val recordError = com.jack.ai.RecordService.error
    LaunchedEffect(recordError) {
        if (recordError != null) {
            com.jack.ai.RecordService.error = null
            Toast.makeText(ctx, recordError, Toast.LENGTH_LONG).show()
        }
    }
    // 输入框自己管光标：外面塞进来的字（建议、分享、语音、@）都把光标放到最后，接着打就行
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    // 剪贴板里有图片（截图、复制的图）：输入框上面出一个「粘贴图片」，点一下加进来。
    // 只看剪贴板的说明（不读内容，系统不会弹「已读取剪贴板」）；App 在前台、输入框拿到焦点时都看一眼
    val clipboardSys = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    var clipShown by remember { mutableStateOf<Long?>(null) }
    var clipDone by rememberSaveable { mutableStateOf(0L) }
    fun checkClip() {
        val d = runCatching { clipboardSys?.primaryClipDescription }.getOrNull()
        clipShown = d?.takeIf { it.hasMimeType("image/*") && it.timestamp != clipDone }?.timestamp
    }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(Unit) { snapshotFlow { windowInfo.isWindowFocused }.collect { if (it) checkClip() } }
    androidx.compose.runtime.DisposableEffect(clipboardSys) {
        val l = android.content.ClipboardManager.OnPrimaryClipChangedListener { checkClip() }
        clipboardSys?.addPrimaryClipChangedListener(l)
        onDispose { clipboardSys?.removePrimaryClipChangedListener(l) }
    }
    fun pasteClipImage() {
        val ts = clipShown ?: return
        clipDone = ts
        clipShown = null
        val clip = runCatching { clipboardSys?.primaryClip }.getOrNull() ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) Toast.makeText(ctx, "没读到剪贴板里的图片", Toast.LENGTH_SHORT).show() else vm.addImages(uris)
    }
    val showClip = clipShown != null && editing == null
    // 打「@」就地列出技能、邮箱，点一下补全
    val mentionNames = remember(settings) {
        settings.activeSkills().map { it.name } +
            if (settings.activeSkills().any { it.id == com.jack.ai.data.SkillIds.MAIL }) settings.readyMails.map { it.address } else emptyList()
    }
    val mentionHits = mentionQuery(field.text, field.selection.end)
        ?.takeIf { field.selection.collapsed }
        ?.let { mentionCandidates(mentionNames, it, field.text) }
        .orEmpty()
    mentionPick?.let { options ->
        AlertDialog(
            onDismissRequest = { mentionPick = null },
            title = { Text("@") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onTextChange(withMention(text, name)); mentionPick = null; runCatching { focus.requestFocus() } }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mentionPick = null }) { Text("取消") } },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (state.compressingId != null && state.compressingId == state.currentId) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("正在压缩较早的对话…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        com.jack.ai.RecordService.current?.let { RecordBar(it) }
        // 正在改一条发过的消息：输入框上面标一下，随时可以取消
        androidx.compose.animation.AnimatedVisibility(editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)) {
                Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = colors.primary)
                Spacer(Modifier.size(8.dp))
                Text("编辑消息 · 发出后从这条重新回答", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelEdit) { Text("取消") }
            }
        }
        Surface(
            shape = Radii.xl,
            // 浮在消息上面的一个大胶囊：左边加号，中间打字，右边一个圆按钮（话筒 / 发送 / 停止）
            color = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else colors.outlineVariant),
            shadowElevation = if (dark) 0.dp else 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(6.dp)) {
                if (state.pending.isNotEmpty() || state.importing || showClip) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        if (showClip) item(key = "clip") {
                            InputChip(
                                selected = false,
                                onClick = { pasteClipImage() },
                                label = { Text("粘贴图片") },
                                leadingIcon = { Icon(AppIcons.Image, null, Modifier.size(18.dp)) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, "不用", Modifier.size(16.dp).clickable { clipDone = clipShown ?: 0L; clipShown = null })
                                },
                            )
                        }
                        items(state.pending, key = { it.id }) { att -> PendingChip(att) { vm.removePending(att.id) } }
                        if (state.importing) {
                            item { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
                if (mentionHits.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    ) {
                        items(mentionHits, key = { it }) { name ->
                            androidx.compose.material3.SuggestionChip(
                                onClick = {
                                    val (t, c) = completeMention(field.text, field.selection.end, name)
                                    field = TextFieldValue(t, TextRange(c))
                                    onTextChange(t)
                                },
                                label = { Text("@$name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.padding(bottom = 2.dp)) {
                        val plusPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        // 开着思考或联网时，加号用强调色，不点开也看得出来
                        val modeOn = settings.thinking != ThinkLevel.OFF && settings.endpoint()?.reasoning != false
                        Box(
                            Modifier.pressScale(plusPress, 0.88f).size(42.dp).clip(CircleShape)
                                .clickable(interactionSource = plusPress, indication = androidx.compose.foundation.LocalIndication.current) { haptics.tick(); attachMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, "更多", Modifier.size(26.dp), tint = if (modeOn) colors.primary else colors.onSurface)
                        }
                        RoundMenu(expanded = attachMenu, onDismiss = { attachMenu = false }) {
                            MenuRow("拍照", icon = AppIcons.Camera) { attachMenu = false; startCamera() }
                            MenuRow("图片", icon = AppIcons.Image) {
                                attachMenu = false
                                try { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                            MenuRow("文件", icon = AppIcons.File) {
                                attachMenu = false
                                try { pickFiles.launch(arrayOf("*/*")) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                            MenuRow("录音纪要", icon = AppIcons.Mic, enabled = com.jack.ai.RecordService.current == null) { attachMenu = false; startRecord() }
                            val skillNames = settings.activeSkills().map { it.name }
                            if (skillNames.isNotEmpty()) MenuRow("@技能", glyph = "@") { attachMenu = false; mentionPick = skillNames }
                            val mailNames = settings.readyMails.map { it.address }
                            if (mailNames.isNotEmpty() && settings.activeSkills().any { it.id == com.jack.ai.data.SkillIds.MAIL }) MenuRow("@邮箱", icon = Icons.Default.Email) {
                                attachMenu = false
                                // 只有一个邮箱就直接插进去
                                if (mailNames.size == 1) onTextChange(withMention(text, mailNames[0])) else mentionPick = mailNames
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = colors.onSurface.copy(alpha = 0.08f))
                            // 不支持深度思考的模型不显示思考开关
                            if (settings.endpoint()?.reasoning != false) MenuRow(settings.thinking.label, icon = AppIcons.Brain, checked = settings.thinking != ThinkLevel.OFF) {
                                attachMenu = false; thinkMenu = true
                            }
                            MenuRow("联网搜索", icon = AppIcons.Globe, checked = settings.webSearch && settings.webReady()) {
                                attachMenu = false
                                if (!settings.webReady()) onNeedTavily()
                                else vm.updateSettings { it.copy(webSearch = !it.webSearch) }
                            }
                            val conv = state.current
                            if (conv != null && conv.messages.size >= 4) MenuRow(
                                "压缩上下文", icon = AppIcons.Compress,
                                sub = "${conv.lastPromptTokens / 1000}k / ${settings.compressTokens / 1000}k",
                                enabled = !state.streamingHere && state.compressingId == null,
                            ) { attachMenu = false; vm.compress() }
                        }
                        // 思考档位：从上面那一行点进来
                        RoundMenu(expanded = thinkMenu, onDismiss = { thinkMenu = false }) {
                            ThinkLevel.entries.forEach { lv ->
                                MenuRow(lv.label, icon = AppIcons.Brain, checked = lv == settings.thinking) {
                                    vm.updateSettings { it.copy(thinking = lv) }; thinkMenu = false
                                }
                            }
                        }
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = field,
                        onValueChange = { field = it; if (it.text != text) onTextChange(it.text) },
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                        modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) checkClip() },
                        decorationBox = { inner ->
                            Box {
                                if (field.text.isEmpty()) Text("问 Kavi…", color = colors.outline, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                    )
                    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sendPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    if (editing == null && state.streamingHere && text.isNotBlank()) {
                        // 回答进行中也能发：作为插话，下一步就会看到
                        Surface(onClick = { if (vm.send(text)) { haptics.tick(); onTextChange("") } }, shape = CircleShape, color = colors.primary,
                            modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUp, "插话", Modifier.size(20.dp), tint = colors.onPrimary)
                            }
                        }
                    }
                    // 右下角的圆按钮：话筒、发送、停止之间换的时候缩放淡入，不生硬地闪一下；按下去缩一下
                    val action = when {
                        editing != null -> SendAction.EDIT
                        state.streamingHere -> SendAction.STOP
                        text.isBlank() && state.pending.isEmpty() -> SendAction.MIC
                        else -> SendAction.SEND
                    }
                    androidx.compose.animation.AnimatedContent(
                        targetState = action,
                        modifier = Modifier.pressScale(sendPress, 0.88f),
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(160), initialScale = 0.6f)) togetherWith
                                (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) +
                                    androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(90), targetScale = 0.6f))
                        },
                        label = "send",
                    ) { a ->
                        when (a) {
                            SendAction.STOP -> Surface(onClick = { haptics.tick(); vm.stop() }, shape = CircleShape, color = colors.onSurface, modifier = Modifier.size(42.dp), interactionSource = sendPress) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Stop, "停止", Modifier.size(16.dp), tint = colors.surface)
                                }
                            }
                            SendAction.MIC -> Surface(onClick = { haptics.tick(); startVoice() }, shape = CircleShape, color = colors.primary, modifier = Modifier.size(42.dp), interactionSource = sendPress) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Mic, "语音输入", Modifier.size(20.dp), tint = colors.onPrimary)
                                }
                            }
                            else -> {
                                val edit = a == SendAction.EDIT
                                val canSend = if (edit) text.isNotBlank() || editing?.attachments?.isNotEmpty() == true
                                    else (text.isNotBlank() || state.pending.isNotEmpty()) && !state.importing
                                Surface(
                                    onClick = { haptics.tick(); if (edit) onSubmitEdit() else if (vm.send(text)) onTextChange("") },
                                    enabled = canSend,
                                    shape = CircleShape,
                                    color = if (canSend) colors.primary else colors.surfaceVariant,
                                    modifier = Modifier.size(42.dp),
                                    interactionSource = sendPress,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(if (edit) Icons.Default.Check else AppIcons.ArrowUp, if (edit) "改好发送" else "发送", Modifier.size(20.dp),
                                            tint = if (canSend) colors.onPrimary else colors.outline)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

private enum class SendAction { MIC, SEND, STOP, EDIT }

@Composable
private fun PendingChip(att: Attachment, onRemove: () -> Unit) {
    if (att.kind == "image") {
        Box {
            Thumbnail(att.path, 56.dp)
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp).align(Alignment.TopEnd)) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
                    Icon(Icons.Default.Close, "移除", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    } else {
        InputChip(
            selected = false,
            onClick = onRemove,
            label = { Text(att.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(AppIcons.File, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.Close, "移除", Modifier.size(16.dp)) },
        )
    }
}

/** 「改成我想要的说法」发给 Kavi 的话：两版原文，要它找出说话方式上的规律、记进记忆。 */
internal fun restyleRequest(original: String, edited: String): String =
    "我把你的一条回答改成了我想要的说法。对比两版，找出我改的规律（说话方式上的，不是这条的内容），" +
        "用 save_memory 记进关于我的记忆里「我希望你怎么说话」那一条（已有就改那条，不另起一条）：原来的规律留着，写不下就压缩，别只抄这次的例子。改完告诉我你总结出了什么。\n\n" +
        "【你的原话】\n" + original.take(2000) + "\n\n【我改成】\n" + edited.take(2000)

/** 在输入框末尾插入「@名字 」，前面不是空白就先补个空格。 */
private fun withMention(text: String, name: String): String {
    if ("@$name" in text) return text
    val sep = if (text.isEmpty() || text.last().isWhitespace()) "" else " "
    return "$text$sep@$name "
}

/** 新对话顶部的 Chat / Work 切换。Work 不限工具轮数。 */
/** 空白页的任务模板：点一下填进输入框，长按修改或删除，最后一格新建。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkTemplates(vm: ChatViewModel, templates: List<com.jack.ai.data.Preset>, onUse: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<com.jack.ai.data.Preset?>(null) }
    val tiles: List<com.jack.ai.data.Preset?> = templates + null
    tiles.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { t ->
                SuggestionCard(
                    icon = if (t != null) AppIcons.Work else null,
                    tint = colors.onSurface,
                    title = t?.name ?: "新模板",
                    modifier = Modifier.weight(1f),
                    muted = t == null,
                    onLongClick = { if (t != null) editing = t },
                ) { if (t == null) editing = com.jack.ai.data.Preset(name = "", prompt = "") else onUse(t.prompt) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
    editing?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        var prompt by remember(t.id) { mutableStateOf(t.prompt) }
        val exists = templates.any { it.id == t.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (exists) "修改模板" else "新模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("要 AI 做什么") }, minLines = 4, maxLines = 8,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
                    val u = t.copy(name = name.trim(), prompt = prompt.trim())
                    vm.updateSettings { s -> s.copy(workTemplates = if (exists) s.workTemplates.map { if (it.id == u.id) u else it } else s.workTemplates + u) }
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (exists) TextButton(onClick = {
                        vm.updateSettings { s -> s.copy(workTemplates = s.workTemplates.filterNot { it.id == t.id }) }
                        editing = null
                    }) { Text("删除", color = colors.error) }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}

/** 聊天列表的「跟着往下走」：on 为真时内容一长就贴到底；用户往上翻就关掉，回到底部再打开。 */
internal class FollowBottom {
    var on by mutableStateOf(true)
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            // 手指往下拖 = 往上翻看旧内容：不再拽着用户
            if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0) on = false
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
}

/**
 * key 换了（换对话）或 newTurn 换了（用户发了新消息）就回到最底下并开始跟；
 * moving（正在输出或字还没放完）时每一帧往底部滑一点（[glide]），新起一行时画面平滑上移，不跳。
 */
@Composable
internal fun rememberFollowBottom(listState: androidx.compose.foundation.lazy.LazyListState, key: Any, newTurn: Any?, moving: Boolean): FollowBottom {
    val f = remember(key) { FollowBottom() }
    suspend fun toEnd() {
        val n = listState.layoutInfo.totalItemsCount
        if (n > 0) listState.scrollToItem(n - 1, Int.MAX_VALUE)
    }
    // 输出中往下跟：不一下子跳到底，而是每帧走剩下距离的五分之一。每写出新的一行，画面在零点二秒左右里滑上去，
    // 不再整屏一下一下地跳（每秒 100 字大约每秒新起 5 行，直接跳就是每秒整屏闪 5 下）
    // 离最底下还差多少像素；最后一条还没露出来时返回 null（离得太远）
    fun distToEnd(): Float? {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return null
        if (last.index < info.totalItemsCount - 1) return null
        return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
    }
    suspend fun glide(share: Float = 0.2f) {
        val dist = distToEnd() ?: run { toEnd(); return } // 离得太远（最后一条还没露出来）：直接到底
        // 最后一点点直接走完，不留几个像素的缝
        if (dist > 0.5f) listState.scrollBy(if (dist <= 2f) dist else maxOf(1f, dist * share))
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { more -> if (!more) f.on = true }
    }
    LaunchedEffect(key, newTurn) {
        f.on = true
        withFrameNanos { }
        toEnd()
    }
    LaunchedEffect(key, moving) {
        if (!moving) {
            // 输出刚停：按同样的滑法把剩下那点距离走完，停在正好贴底的位置（用户在翻看、在拖动时不动）
            repeat(60) {
                withFrameNanos { }
                if (!f.on || listState.isScrollInProgress) return@LaunchedEffect
                val d = distToEnd() ?: return@LaunchedEffect
                if (d <= 0.5f) return@LaunchedEffect
                glide(share = 0.3f)
            }
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            if (f.on && listState.canScrollForward && !listState.isScrollInProgress) glide()
        }
    }
    return f
}
