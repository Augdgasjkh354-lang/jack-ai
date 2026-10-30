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
import com.jack.ai.data.Pricing
import androidx.compose.runtime.produceState
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
                // 顶栏透明：背景色从上往下渐变到全透明，消息滚上去时自然淡出
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.9f), bg.copy(alpha = 0f)))),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                conv?.takeIf { it.messages.isNotEmpty() }?.title ?: "Kavi",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                            ModelPicker(state.settings, project?.name) { m -> vm.updateSettings { it.copy(model = m) } }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(AppIcons.Menu, contentDescription = "菜单", Modifier.size(24.dp))
                        }
                    },
                    actions = {
                        if (conv != null && conv.messages.isNotEmpty()) {
                            IconButton(onClick = { showFiles = true }) {
                                // Work：文件夹图标上标出做出来的文件数
                                val made = if (conv.work) conv.messages.flatMap { it.files }.distinctBy { it.path }.size else 0
                                if (made > 0) {
                                    androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("$made") } }) {
                                        Icon(AppIcons.Folder, contentDescription = "交付物", Modifier.size(22.dp))
                                    }
                                } else Icon(AppIcons.Folder, contentDescription = "文件夹", Modifier.size(22.dp))
                            }
                        }
                        IconButton(onClick = { vm.newChat(state.currentProjectId) }) {
                            Icon(AppIcons.NewChat, contentDescription = "新对话", Modifier.size(22.dp))
                        }
                        if (conv != null && conv.messages.isNotEmpty()) ExportMenu(vm, onMemory = { memoryOf = conv })
                    },
                )
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
                if (conv == null || conv.messages.isEmpty()) {
                    Box(Modifier.padding(padding)) { EmptyState(vm, state, conv, onOpenMemory, onSuggest = { text = it; focusInput++ }) }
                } else {
                    MessageList(vm, conv, state.streamingHere, padding, editingId = editing?.id, onDeleted = ::onDeleted, onEdit = { m ->
                        if (editing == null) editBackup = text
                        editing = m
                        text = m.content
                        focusInput++
                    })
                }
                // 只在 Kavi 还在回答时出来；答完就收起，免得盖住回答
                BrowserPeek(Modifier.align(Alignment.TopEnd).padding(top = padding.calculateTopPadding() + 4.dp, end = 12.dp), working = state.streaming)
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
private fun ExportMenu(vm: ChatViewModel, onMemory: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val busy by vm.exporting.collectAsState()
    val ctx = LocalContext.current
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("记忆") },
                leadingIcon = { Icon(AppIcons.Moon, null) },
                onClick = { open = false; onMemory() },
            )
            HorizontalDivider()
            listOf(
                ChatExport.Format.IMAGE to "分享为长图",
                ChatExport.Format.PDF to "导出为 PDF",
                ChatExport.Format.MARKDOWN to "导出为 Markdown",
            ).forEach { (fmt, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(AppIcons.Share, null) },
                    onClick = {
                        open = false
                        vm.exportChat(fmt) { file -> shareExport(ctx, file, fmt.mime) }
                    },
                )
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
private fun ModelPicker(settings: Settings, projectName: String?, onPick: (String) -> Unit) {
    val model = settings.model
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { open = true }.padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                (projectName?.let { "$it · " } ?: "") + modelLabel(model),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 460.dp)) {
            val choices = settings.modelChoices()
            val groups = choices.map { it.group }.distinct()
            choices.forEachIndexed { i, c ->
                // 有多个服务时，每组前面标一下服务名
                if (groups.size > 1 && (i == 0 || choices[i - 1].group != c.group)) {
                    if (i > 0) HorizontalDivider()
                    Text(c.group, style = MaterialTheme.typography.labelMedium, color = colors.outline,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { if (c.key == model) Icon(Icons.Default.Check, null, tint = colors.primary) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onPick(c.key); open = false },
                )
            }
        }
    }
}

/** 空白页的建议：图标、图标底色（浅色、深色模式各一个）、标题、点了填进输入框的话。 */
private data class Suggestion(val icon: ImageVector, val tint: Long, val title: String, val prompt: String)

// 点一下只是填进输入框，不直接发；要补的内容放在最后，光标就在那里
private val SUGGESTIONS = listOf(
    Suggestion(AppIcons.Globe, 0xFF3B82F6, "今天有什么新闻", "今天有什么值得关注的新闻？"),
    Suggestion(AppIcons.Calendar, 0xFFEF5A5A, "帮我记一件事", "提醒我"),
    Suggestion(AppIcons.Speaker, 0xFF8B5CF6, "读个网页给我听", "帮我打开这个网页，读完告诉我重点："),
    Suggestion(AppIcons.File, 0xFF22A06B, "整理成表格", "帮我把下面的内容整理成表格：\n"),
)

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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModeSwitch(work = conv?.work ?: state.draftWork, onChange = vm::setWork)
        Spacer(Modifier.height(36.dp))
        LogoMark(64.dp)
        Spacer(Modifier.height(22.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？"
            else todayLine(state.routines) ?: "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (conv?.work ?: state.draftWork) {
            WorkTemplates(vm, state.settings.workTemplates, onSuggest)
        } else SUGGESTIONS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { sg -> SuggestionCard(sg.icon, Color(sg.tint), sg.title, Modifier.weight(1f)) { onSuggest(sg.prompt) } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 空白页的一张建议卡：左上一个浅色底的彩色小图标，下面是标题，右边一个小箭头。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SuggestionCard(icon: ImageVector?, tint: Color, title: String, modifier: Modifier = Modifier, muted: Boolean = false,
                           onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    // 按下去时卡片微微缩一下，手感更实
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .background(colors.surfaceContainerLowest)
            .combinedClickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
    ) {
        Column {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = tint)
                else Text("＋", color = tint, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (muted) null else FontWeight.SemiBold,
                    color = if (muted) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.outline)
            }
        }
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
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) colors.primaryContainer else Color.Transparent,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (active) null else BorderStroke(1.dp, colors.outlineVariant),
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
                        editingId: String?, onDeleted: (Int) -> Unit, onEdit: (com.jack.ai.data.ChatMessage) -> Unit) {
    // 一打开就停在最后一条附近，不先闪一下最顶上
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
                                msg = msg,
                                streaming = streaming && isLast,
                                isLast = isLast,
                                onRegenerate = vm::regenerate,
                                onDelete = { vm.deleteFrom(msg.id).let { if (it > 0) onDeleted(it) } },
                                onReview = vm::reviewLast,
                                onContinue = if (conv.work && isLast && !msg.truncated && vm.canContinue(msg)) ({ vm.continueWork() }) else null,
                                onContinueWriting = vm::continueWriting,
                                onReport = { note -> vm.reportIssue(msg.id, note) },
                                onFixApp = { text -> vm.send(text) },
                                work = conv.work,
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
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
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
    Surface(shape = RoundedCornerShape(18.dp), color = colors.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Box(Modifier.size(10.dp).background(if (paused) colors.outline else colors.error, CircleShape))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text((if (paused) "暂停 " else "录音 ") + com.jack.ai.data.AudioPcm.stamp(session.elapsed(now) / 1000),
                    style = MaterialTheme.typography.titleSmall, color = colors.onErrorContainer)
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
            shape = RoundedCornerShape(28.dp),
            // 浮在消息上面的白色大胶囊，带一点柔和的阴影
            color = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = if (dark) 0.dp else 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
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
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp)) {
                        Surface(
                            onClick = { attachMenu = true },
                            shape = CircleShape,
                            color = colors.surfaceContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, "更多", Modifier.size(22.dp), tint = colors.onSurface)
                            }
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = { Icon(AppIcons.Camera, null) },
                                onClick = {
                                    attachMenu = false
                                    startCamera()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("录音纪要") },
                                leadingIcon = { Icon(AppIcons.Mic, null) },
                                enabled = com.jack.ai.RecordService.current == null,
                                onClick = {
                                    attachMenu = false
                                    startRecord()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("图片") },
                                leadingIcon = { Icon(AppIcons.Image, null) },
                                onClick = {
                                    attachMenu = false
                                    try { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("文件") },
                                leadingIcon = { Icon(AppIcons.File, null) },
                                onClick = {
                                    attachMenu = false
                                    try { pickFiles.launch(arrayOf("*/*")) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                                },
                            )
                            val skillNames = settings.activeSkills().map { it.name }
                            if (skillNames.isNotEmpty()) DropdownMenuItem(
                                text = { Text("@技能") },
                                leadingIcon = { Text("@", style = MaterialTheme.typography.titleMedium) },
                                onClick = { attachMenu = false; mentionPick = skillNames },
                            )
                            val mailNames = settings.readyMails.map { it.address }
                            if (mailNames.isNotEmpty() && settings.activeSkills().any { it.id == com.jack.ai.data.SkillIds.MAIL }) DropdownMenuItem(
                                text = { Text("@邮箱") },
                                leadingIcon = { Icon(Icons.Default.Email, null) },
                                onClick = {
                                    attachMenu = false
                                    // 只有一个邮箱就直接插进去
                                    if (mailNames.size == 1) onTextChange(withMention(text, mailNames[0])) else mentionPick = mailNames
                                },
                            )
                            val conv = state.current
                            if (conv != null && conv.messages.size >= 4) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("压缩上下文")
                                            Text(
                                                "${conv.lastPromptTokens / 1000}k / ${settings.compressTokens / 1000}k",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(AppIcons.Compress, null) },
                                    enabled = !state.streamingHere && state.compressingId == null,
                                    onClick = {
                                        attachMenu = false
                                        vm.compress()
                                    },
                                )
                            }
                        }
                    }
                    TextField(
                        value = field,
                        onValueChange = { field = it; if (it.text != text) onTextChange(it.text) },
                        placeholder = { Text("问 Kavi…", color = colors.outline) },
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) checkClip() },
                    )
                }
                // 第二行：思考、联网两个小药丸，右边是话筒 / 发送 / 停止
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)) {
                    // 不支持深度思考的模型不显示思考开关
                    if (settings.endpoint()?.reasoning != false) Box {
                        Pill(
                            label = settings.thinking.label,
                            active = settings.thinking != ThinkLevel.OFF,
                            icon = AppIcons.Brain,
                            dropdown = true,
                            onClick = { thinkMenu = true },
                        )
                        DropdownMenu(expanded = thinkMenu, onDismissRequest = { thinkMenu = false }) {
                            ThinkLevel.entries.forEach { lv ->
                                DropdownMenuItem(
                                    text = { Text(lv.label) },
                                    leadingIcon = {
                                        if (lv == settings.thinking) Icon(Icons.Default.Check, null, tint = colors.primary)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = { vm.updateSettings { it.copy(thinking = lv) }; thinkMenu = false },
                                )
                            }
                        }
                    }
                    Pill(
                        label = "联网",
                        active = settings.webSearch && (settings.webReady()),
                        icon = AppIcons.Globe,
                        onClick = {
                            if (!settings.webReady()) onNeedTavily()
                            else vm.updateSettings { it.copy(webSearch = !it.webSearch) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    // DeepSeek 高峰时段价格翻倍，提示一下（每分钟刷新）
                    val now by produceState(System.currentTimeMillis()) {
                        while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() }
                    }
                    if (settings.endpoint()?.deepseek == true && Pricing.isPeak(now)) {
                        Text("高峰 ×2", style = MaterialTheme.typography.labelSmall, color = colors.outline,
                            modifier = Modifier.padding(end = 2.dp))
                    }
                    if (editing == null && state.streamingHere && text.isNotBlank()) {
                        // 回答进行中也能发：作为插话，下一步就会看到
                        Surface(onClick = { if (vm.send(text)) onTextChange("") }, shape = CircleShape, color = colors.primary,
                            modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUp, "插话", Modifier.size(20.dp), tint = colors.onPrimary)
                            }
                        }
                    }
                    // 右下角的圆按钮：话筒、发送、停止之间换的时候缩放淡入，不生硬地闪一下
                    val action = when {
                        editing != null -> SendAction.EDIT
                        state.streamingHere -> SendAction.STOP
                        text.isBlank() && state.pending.isEmpty() -> SendAction.MIC
                        else -> SendAction.SEND
                    }
                    androidx.compose.animation.AnimatedContent(
                        targetState = action,
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(160), initialScale = 0.6f)) togetherWith
                                (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) +
                                    androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(90), targetScale = 0.6f))
                        },
                        label = "send",
                    ) { a ->
                        when (a) {
                            SendAction.STOP -> Surface(onClick = { vm.stop() }, shape = CircleShape, color = colors.onSurface, modifier = Modifier.size(42.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Stop, "停止", Modifier.size(16.dp), tint = colors.surface)
                                }
                            }
                            SendAction.MIC -> Surface(onClick = { startVoice() }, shape = CircleShape, color = colors.primary, modifier = Modifier.size(42.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Mic, "语音输入", Modifier.size(20.dp), tint = colors.onPrimary)
                                }
                            }
                            else -> {
                                val edit = a == SendAction.EDIT
                                val canSend = if (edit) text.isNotBlank() || editing?.attachments?.isNotEmpty() == true
                                    else (text.isNotBlank() || state.pending.isNotEmpty()) && !state.importing
                                Surface(
                                    onClick = { if (edit) onSubmitEdit() else if (vm.send(text)) onTextChange("") },
                                    enabled = canSend,
                                    shape = CircleShape,
                                    color = if (canSend) colors.primary else colors.surfaceVariant,
                                    modifier = Modifier.size(42.dp),
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

/** 在输入框末尾插入「@名字 」，前面不是空白就先补个空格。 */
private fun withMention(text: String, name: String): String {
    if ("@$name" in text) return text
    val sep = if (text.isEmpty() || text.last().isWhitespace()) "" else " "
    return "$text$sep@$name "
}

/** 新对话顶部的 Chat / Work 切换。Work 不限工具轮数。 */
@Composable
private fun ModeSwitch(work: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = colors.surfaceVariant) {
        Row(Modifier.padding(3.dp)) {
            listOf(false to "Chat", true to "Work").forEach { (w, label) ->
                val on = w == work
                Surface(
                    onClick = { onChange(w) },
                    shape = CircleShape,
                    color = if (on) colors.surface else Color.Transparent,
                    shadowElevation = if (on) 1.dp else 0.dp,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                        color = if (on) colors.onSurface else colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** Work 空白页的模板：点一下填进输入框，长按修改或删除，最后一格新建。 */
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
