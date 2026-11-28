package com.jack.ai.ui

import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
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
import com.jack.ai.undoDelete
import com.jack.ai.consumeIncomingText
import com.jack.ai.editMessage
import com.jack.ai.deleteFrom
import com.jack.ai.switchBranch
import com.jack.ai.regenerate
import com.jack.ai.continueWriting

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
    onOpenFiles: () -> Unit = {},
    onOpenGames: () -> Unit = {},
    onOpenPage: (String) -> Unit = {},
    /** 点底栏：主界面给了就交给它（淡入淡出地换页）；没给就按各自的入口打开。 */
    onOpenTab: ((KaviTab) -> Unit)? = null,
    /** 打开终端，参数是对话文件夹的 key（Workspace.key）。 */
    onOpenTerminal: (String) -> Unit = {},
    /** 新手任务页。 */
    onOpenGuide: () -> Unit = {},
    /** 直接打开设置里的某一页（比如缺搜索 Key 时打开「连接」）。 */
    onOpenSettingsPage: (String) -> Unit = { onOpenSettings() },
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
    // 从别的页面开了新对话回来（项目页的「新对话」）：同样光标进输入框、键盘起来
    LaunchedEffect(ChatFocus.request) { if (ChatFocus.request) { ChatFocus.request = false; focusInput++ } }
    LaunchedEffect(ChatFocus.prefill, conv?.id) { ChatFocus.prefill?.let { p -> ChatFocus.prefill = null; text = p; focusInput++ } }
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
    // 发不出去、要去设置里补一样东西（额度用完、没有能看图的模型）：底下一条提示，点「去看看」直接到那页
    LaunchedEffect(state.toastGo) {
        val (msg, page) = state.toastGo ?: return@LaunchedEffect
        vm.consumeToastGo()
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(msg, actionLabel = "去看看", duration = androidx.compose.material3.SnackbarDuration.Long)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettingsPage(page)
    }
    // 从顶栏「更多」删掉眼前的对话：回到空白页，底下出「撤销」，撤销了再切回来
    fun deleteChat(c: Conversation) {
        vm.delete(c.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("删除了「${c.title.take(12)}」", actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed && vm.undoDeleteConversation()) vm.select(c.id)
        }
    }
    // 引用的 Kavi 的一段话：放在输入框上面，发出去时带在前面；换了对话就不带了
    var quote by remember(conv?.id) { mutableStateOf<String?>(null) }
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
    // 正在改一条发过的消息：返回键就是「取消」，原来没写完的草稿放回输入框（以前一按返回直接退出了）
    androidx.activity.compose.BackHandler(enabled = editing != null && !drawerState.isOpen) { stopEditing() }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 关着时由 swipeToOpen 负责打开：抽屉自带的拖动碰到消息气泡会失灵，两个一起开还会互相拉扯（刚打开又被拉回去）。
        // 开着时交还给抽屉，往左划关上
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HistoryDrawer(
                state = state,
                onNew = { vm.newChat(); focusInput++; scope.launch { drawerState.close() } },
                onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                onDelete = vm::delete,
                onUndoDelete = vm::undoDeleteConversation,
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
                onOpenFiles = { scope.launch { drawerState.close() }; onOpenFiles() },
                onOpenGames = { scope.launch { drawerState.close() }; onOpenGames() },
                onOpenPage = { rel -> scope.launch { drawerState.close() }; onOpenPage(rel) },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                onCloseDrawer = { scope.launch { drawerState.close() } },
                onSaveTabs = { ids -> vm.updateSettings { it.copy(tabBar = ids) } },
                onSaveOrder = { ids -> vm.updateSettings { it.copy(drawerOrder = ids) } },
            )
        },
    ) {
        // 毛玻璃：消息那一层是被模糊的底，顶栏、输入框和上面的圆按钮从它取模糊
        val glass = remember { dev.chrisbanes.haze.HazeState() }
        androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass, LocalBubbleAccent provides state.settings.bubbleAccent) {
        Scaffold(
            containerColor = bg,
            snackbarHost = { KaviSnackbarHost(snackbar) },
            topBar = {
                Column {
                // 顶栏是一条渐变的毛玻璃：靠屏幕顶的地方最糊，往下渐渐变清楚，滚上来的消息不和标题、模型名叠在一起看不清
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                    ),
                    // 空白页没有消息会滚上来，顶栏全透明，让那一角天露出来
                    modifier = if (conv == null || conv.messages.isEmpty()) Modifier else Modifier.glassBar(top = true),
                    title = {
                        // 中间一个胶囊：对话标题，下面是模型名，点一下换模型
                        Box(Modifier.tourTarget("model")) {
                            ModelPicker(conv?.takeIf { it.messages.isNotEmpty() }?.title ?: "Kavi", state.settings, project?.name,
                                onManage = { onOpenSettingsPage("PROVIDERS") }) { m -> vm.updateSettings { it.copy(model = m) } }
                        }
                    },
                    navigationIcon = {
                        CircleIconButton(AppIcons.Menu, "菜单", Modifier.padding(start = 12.dp).tourTarget("menu")) { scope.launch { drawerState.open() } }
                    },
                    actions = {
                        Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 开了新对话就是要打字：光标放进输入框、键盘起来
                            CircleIconButton(AppIcons.NewChat, "新对话", Modifier.tourTarget("newchat")) { vm.newChat(state.currentProjectId); focusInput++ }
                            // 文件夹收在「更多」里（做出了文件时，「更多」上标个数），顶栏右边只留两个圆按钮，标题胶囊才居中
                            if (conv != null && conv.messages.isNotEmpty()) ExportMenu(vm, conv,
                                made = conv.messages.flatMap { it.files }.distinctBy { it.path }.size,
                                onFiles = { showFiles = true }, onMemory = { memoryOf = conv },
                                onDelete = { deleteChat(conv) },
                                onTerminal = if (com.jack.ai.Edition.linux) { { onOpenTerminal(com.jack.ai.data.Workspace.key(conv)) } } else null)
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
                        OutlinedButton(onClick = { vm.newChat(state.currentProjectId); focusInput++ }) { Text("开一个新对话") }
                    }
                } else Box(if (conv == null || conv.messages.isEmpty()) Modifier else Modifier.glassBar(top = false)) { InputBar(
                    vm = vm,
                    state = state,
                    text = text,
                    onTextChange = { text = it },
                    focusRequest = focusInput,
                    editing = editing,
                    onCancelEdit = { stopEditing() },
                    quote = quote,
                    onClearQuote = { quote = null },
                    onSubmitEdit = { editing?.let { m -> vm.editMessage(m.id, text) }; stopEditing() },
                    onNeedTavily = {
                        // 直接打开「连接」那页，不用自己从设置首页找
                        Toast.makeText(ctx, "在「联网搜索」里填 Exa 或 Tavily 的 Key 就能用", Toast.LENGTH_LONG).show()
                        onOpenSettingsPage("CONNECT")
                    },
                    onNeedSpeech = { onOpenSettingsPage("KEY") },
                    // 标签栏：聊天（就是这里）、资料库、记忆、浏览器、提醒，一点就过去
                    // 放哪几个、什么顺序，在侧边栏里长按拖动改（见 TabArranger）
                    tabs = {
                        LiquidTabBar(KaviTab.bar(state.settings.tabBar).map { t ->
                            TabItem(t.icon, t.label, selected = t == KaviTab.CHAT) {
                                if (t != KaviTab.CHAT && onOpenTab != null) onOpenTab(t) else when (t) {
                                    KaviTab.CHAT -> focusInput++
                                    KaviTab.BROWSER -> BrowserTabActivity.open(ctx)
                                    KaviTab.ROUTINES -> onOpenRoutines()
                                    else -> when (SubPage.lastOf(t)) {
                                        SubPage.LIBRARY, null -> onOpenLibrary()
                                        SubPage.FILES -> onOpenFiles()
                                        SubPage.TABLES -> onOpenTables()
                                        SubPage.DOWNLOADS -> onOpenDownloads()
                                        SubPage.MEMORY -> onOpenMemory()
                                        SubPage.REVIEW -> onOpenReview()
                                        SubPage.SKILLS -> onOpenSkills()
                                        SubPage.APPS -> onOpenApps()
                                        SubPage.GAMES -> onOpenGames()
                                    }
                                }
                            }
                        }, selected = 0, tourKey = "tabs")
                    },
                ) }
            },
        ) { padding ->
            // 消息铺满整屏，从上下两个半透明的框后面滚过去
            GlassSource(glass, Modifier.fillMaxSize().swipeToOpen { scope.launch { drawerState.open() } }) {
                // Kavi 在用浏览器、回答里的实时画面又不在屏幕上时，网页窗口的落点（看不见，垫在消息下面、顶栏后面）
                BrowserHiddenHost(Modifier.align(Alignment.TopStart).padding(top = padding.calculateTopPadding() / 2), working = state.streaming, convId = conv?.id.orEmpty())
                if (conv == null || conv.messages.isEmpty()) {
                    // 和记忆页同一片天：右上角一轮跟着钟点变色的光，深色模式下有星星
                    SkyBackdrop(Modifier.fillMaxWidth().height(320.dp), key = conv?.id)
                    Box(Modifier.padding(padding)) { EmptyState(vm, state, conv, onOpenMemory, onOpenRoutines = { if (onOpenTab != null) onOpenTab(KaviTab.ROUTINES) else onOpenRoutines() }, onOpenGuide = onOpenGuide) }
                } else {
                    MessageList(vm, conv, state.streamingHere, padding, editingId = editing?.id, onDeleted = ::onDeleted, summaryCollapsed = state.settings.summaryCollapsed,
                        onOpenSettingsPage = onOpenSettingsPage, settings = state.settings,
                        onQuote = { q -> quote = q; focusInput++ }, onEdit = { m ->
                        if (editing == null) editBackup = text
                        editing = m
                        text = m.content
                        focusInput++
                    })
                }
            }
        }
        }
    }
    memoryOf?.let { c -> ConversationMemoryDialog(vm, c) { memoryOf = null } }
    if (showFiles && conv != null) {
        FilesSheet(vm, conv, inProject = conv.projectId != null, onDismiss = { showFiles = false })
    }
    // 新手教学：填过 DeepSeek Key 以后问一次（老用户也问一次），看不看都算问过；设置里能重看
    val tourKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val tourFocus = androidx.compose.ui.platform.LocalFocusManager.current
    fun startTour() { tourKeyboard?.hide(); tourFocus.clearFocus(); scope.launch { drawerState.close() }; Tour.start(Tour.CHAT) }
    if (state.settings.apiKey.isNotBlank() && !state.settings.tourAsked && Tour.step < 0) TourAsk { yes ->
        vm.updateSettings { it.copy(tourAsked = true) }
        if (yes) startTour()
    }
    LaunchedEffect(Tour.replay) {
        if (!Tour.replay) return@LaunchedEffect
        Tour.replay = false
        kotlinx.coroutines.delay(350) // 等从设置页回来的动画走完、按钮都量好位置
        startTour()
    }
    androidx.activity.compose.BackHandler(enabled = Tour.step >= 0) { Tour.step = -1 }
    TourOverlay(onTry = { text = "你能帮我做什么？"; focusInput++ })
    // 第一次打开侧边栏：带一遍侧边栏里有什么
    val tourCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) Tour.autoStart(tourCtx, Tour.DRAWER, state.settings.tourAsked) }
    TourOverlay(Tour.DRAWER)
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
        // 正在选字（菜单开着）：这一划是在调选区，不开侧边栏
        if (QuoteToolbar.selecting) return@awaitEachGesture
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
            // 按住超过长按的时间还没划开：是长按选字后拖着扩大选区，不是右滑（真右滑一下就划过去了）
            if (change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
            if (dx > distance && dx > abs(dy) * 2) {
                change.consume()
                open()
                break
            }
        }
    }
}

@Composable
private fun ExportMenu(vm: ChatViewModel, conv: Conversation, made: Int, onFiles: () -> Unit, onMemory: () -> Unit, onDelete: () -> Unit, onTerminal: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (renaming) RenameDialog(conv.title, onDismiss = { renaming = false }) { vm.rename(conv.id, it); renaming = false }
    val busy by vm.exporting.collectAsState()
    var sharingImage by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    if (sharingImage) ShareImageSheet(vm, conv, onShare = { f -> shareExport(ctx, f, ChatExport.Format.IMAGE.mime) }) { sharingImage = false }
    Box {
        if (busy) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        else if (made > 0) androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("$made") } }) {
            CircleIconButton(Icons.Default.MoreVert, "更多") { open = true }
        } else CircleIconButton(Icons.Default.MoreVert, "更多") { open = true }
        RoundMenu(expanded = open, onDismiss = { open = false }) {
            MenuRow("文件夹", icon = AppIcons.Folder, sub = if (made > 0) "做出了 $made 个文件" else null) { open = false; onFiles() }
            MenuRow("记忆", icon = AppIcons.Moon) { open = false; onMemory() }
            // 改名、置顶眼前这个对话：以前要打开侧边栏找到它再长按
            MenuRow("重命名", icon = Icons.Default.Edit) { open = false; renaming = true }
            MenuRow(if (conv.pinned) "取消置顶" else "置顶", icon = AppIcons.Pin) { open = false; vm.togglePin(conv.id) }
            // 在这个对话的文件夹里开终端（Kavi 跑的命令也在里面能看到）
            onTerminal?.let { t -> MenuRow("终端", glyph = ">_", sub = "在这个对话的文件夹里") { open = false; t() } }
            // 长图先开选样子的面板（浅色、深色、纸张、卡片），看着预览再分享
            MenuRow("分享为图片", icon = AppIcons.Image, sub = "能选样子，先看预览") { open = false; sharingImage = true }
            listOf(
                ChatExport.Format.PDF to "导出为 PDF",
                ChatExport.Format.MARKDOWN to "导出为 Markdown",
            ).forEach { (fmt, label) ->
                MenuRow(label, icon = AppIcons.Share) {
                    open = false
                    vm.exportChat(fmt) { file -> shareExport(ctx, file, fmt.mime) }
                }
            }
            // 删掉眼前这个对话：以前要打开侧边栏找到它再长按。不先确认，底下出「撤销」
            MenuRow("删除对话", icon = Icons.Default.Delete, danger = true) { open = false; onDelete() }
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
private fun ModelPicker(title: String, settings: Settings, projectName: String?, onManage: () -> Unit, onPick: (String) -> Unit) {
    val model = settings.model
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pressScale(press, 0.95f).heightIn(min = 42.dp).glass(CircleShape)
                .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { open = true }
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // 标题（第一轮答完自动起名）和模型名换了：淡入淡出换过去，胶囊宽度跟着平滑变
            androidx.compose.animation.AnimatedContent(title, modifier = Modifier.animateContentSize(Springs.defaultSpatial()),
                transitionSpec = { fadeIn(Springs.defaultEffects()) togetherWith fadeOut(Springs.fastEffects()) }, label = "title") { t ->
                Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.widthIn(max = 150.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedContent((projectName?.let { "$it · " } ?: "") + modelLabel(model),
                    transitionSpec = { fadeIn(Springs.defaultEffects()) togetherWith fadeOut(Springs.fastEffects()) }, label = "model") { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 136.dp),
                    )
                }
                // 菜单开着时小箭头转上去
                Chevron(open, Modifier.size(14.dp))
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
                    Modifier.fillMaxWidth().clickable { if (c.key != model) haptics.select(); onPick(c.key); open = false }.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = if (c.key == model) colors.primary else colors.onSurface,
                        fontWeight = if (c.key == model) FontWeight.SemiBold else null)
                    if (c.key == model) { Spacer(Modifier.width(12.dp)); Icon(Icons.Default.Check, "当前", Modifier.size(20.dp), tint = colors.primary) }
                }
            }
            // 想要的模型不在里面：直接去加服务、勾模型，不用退出去从设置首页找
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = colors.onSurface.copy(alpha = 0.08f))
            Row(
                Modifier.fillMaxWidth().clickable { open = false; onManage() }.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("添加模型…", color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, conv: Conversation, streaming: Boolean, bars: PaddingValues,
                        editingId: String?, onDeleted: (Int) -> Unit, onEdit: (com.jack.ai.data.ChatMessage) -> Unit,
                        summaryCollapsed: Boolean = false, onOpenSettingsPage: (String) -> Unit = {}, settings: Settings? = null,
                        onQuote: ((String) -> Unit)? = null) {
    // 一打开就停在最后一条附近，不先闪一下最顶上
    val ctx = LocalContext.current
    val listState = remember(conv.id) { androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = (conv.messages.size - 1).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    val lastId = conv.messages.last().id
    val lastUserId = conv.messages.lastOrNull { it.role == "user" }?.id
    val follow = rememberFollowBottom(listState, conv.id, lastUserId, moving = streaming || StreamReveal.pending)
    // 回答还在写时点了「改成我想要的说法」：先存着，这条答完自动交给 Kavi，不用等着再点一次
    var restyleLater by remember(conv.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(streaming, restyleLater) {
        val r = restyleLater ?: return@LaunchedEffect
        if (!streaming && vm.send(r)) restyleLater = null
    }
    val models = settings?.modelChoices().orEmpty()
    val seen = remember(conv.id) { conv.messages.mapTo(HashSet()) { it.id } }
    // 从搜索点进来：等回到底部那一下过去，再滚到命中的那条，停在那里不跟着往下走
    val jump = ChatJump.target?.takeIf { it.first == conv.id }
    LaunchedEffect(conv.id, jump) {
        val id = jump?.second ?: return@LaunchedEffect
        ChatJump.target = null
        val at = messageItemIndex(conv, id)
        if (at < 0) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { }
        follow.on = false
        listState.scrollToItem(at)
    }
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
            conv.messages.forEachIndexed { mi, msg ->
                // 隔了一阵的消息前面：居中一行灰色小字的时间（第一条也有，知道是哪天聊的）
                if (timeBreak(conv.messages.getOrNull(mi - 1), msg)) item(key = "t" + msg.id) { TimeStamp(msg.createdAt) }
                item(key = msg.id) {
                    // 刚发出去、刚开始回的消息：从下面浮上来；打开对话时已有的、滑出去再滑回来的都不播
                    val fresh = remember { msg.id !in seen }.also { seen += msg.id }
                    Box(Modifier.animateItem().then(if (fresh) Modifier.appear(rise = if (msg.role == "user") 28 else 10) else Modifier)) {
                        if (msg.role == "user") {
                            UserBubble(msg, onDelete = { vm.deleteFrom(msg.id).let { if (it > 0) onDeleted(it) } }, onEdit = { onEdit(msg) },
                                editing = msg.id == editingId, onSwitch = { k -> vm.switchBranch(msg.id, k) })
                        } else {
                            val isLast = msg.id == lastId
                            AssistantMessage(
                                convId = conv.id,
                                msg = msg,
                                streaming = streaming && isLast,
                                isLast = isLast,
                                onRegenerate = vm::regenerate,
                                // 换个模型重答：选好就换上并重新回答，不用先去顶上换模型再回来点重答
                                models = if (isLast && models.size > 1) models.filter { it.key != settings?.model }.map { it.key to it.label } else emptyList(),
                                onRegenerateWith = { key -> vm.updateSettings { it.copy(model = key) }; vm.regenerate() },
                                onQuote = onQuote,
                                onDelete = { vm.deleteFrom(msg.id).let { if (it > 0) onDeleted(it) } },
                                onReview = vm::reviewLast,
                                onContinue = if (isLast && !msg.truncated && vm.canContinue(msg)) ({ vm.continueWork() }) else null,
                                onContinueWriting = vm::continueWriting,
                                onReport = { note -> vm.reportIssue(msg.id, note) },
                                onFixApp = { text -> vm.send(text) },
                                onAnswer = { text -> vm.send(text) },
                                onOpenSettings = onOpenSettingsPage,
                                // 列了清单的（在做任务）工具步骤按时间线显示
                                work = msg.plan.isNotEmpty(),
                                stepsCollapsed = summaryCollapsed,
                                onRestyle = if (msg.plan.isNotEmpty()) null else { edited ->
                                    if (streaming) {
                                        restyleLater = restyleRequest(msg.content, edited)
                                        Toast.makeText(ctx, "记下了，这条答完就交给 Kavi", Toast.LENGTH_SHORT).show()
                                    } else vm.send(restyleRequest(msg.content, edited))
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
        // 翻上去了：给一个回到底部的按钮。翻上去以后回答又写了新内容，按钮右上角亮一个小点
        val sig = conv.messages.size to conv.messages.last().content.length + conv.messages.last().files.size
        var leftAt by remember(conv.id) { mutableStateOf(sig) }
        LaunchedEffect(follow.on) { if (!follow.on) leftAt = sig }
        val fresh = !follow.on && sig != leftAt
        androidx.compose.animation.AnimatedVisibility(
            visible = !follow.on && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding() + 12.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.7f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.7f),
        ) {
            Box {
                CircleIconButton(Icons.Default.KeyboardArrowDown, if (fresh) "回到底部（有新内容）" else "回到底部", size = 40.dp) {
                    follow.on = true; scope.launch { val n = listState.layoutInfo.totalItemsCount; if (n > 0) listState.animateScrollToItem(n - 1, Int.MAX_VALUE) }
                }
                androidx.compose.animation.AnimatedVisibility(fresh, Modifier.align(Alignment.TopEnd),
                    enter = androidx.compose.animation.scaleIn(Springs.fastSpatial()), exit = androidx.compose.animation.scaleOut()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
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

/** 回到聊天页时要不要把光标放进输入框、弹出键盘（别的页面开了新对话时设），用一次就清掉。 */
internal object ChatFocus {
    var request by mutableStateOf(false)
    /** 回到聊天页时先填进输入框的话（游戏页的「让 Kavi 改」），用一次就清掉。 */
    var prefill by mutableStateOf<String?>(null)
}

/** 打开对话后要滚到的那条消息（侧边栏搜索点进来时设），用一次就清掉。 */
internal object ChatJump {
    /** (对话 id, 消息 id) */
    var target by mutableStateOf<Pair<String, String>?>(null)
}

/** 列表里第几项是这条消息：已压缩分界线也占一项。找不到返回 -1。 */
internal fun messageItemIndex(conv: Conversation, id: String): Int {
    var i = 0
    var prev: com.jack.ai.data.ChatMessage? = null
    for (m in conv.messages) {
        if (timeBreak(prev, m)) i++
        prev = m
        if (m.id == id) return i
        i++
        if (m.id == conv.summaryUntilId) i++
    }
    return -1
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

/** 两条消息隔了多久才在中间标时间：10 分钟。第一条消息前面总是标。 */
private const val TIME_GAP_MS = 10 * 60_000L

internal fun timeBreak(prev: com.jack.ai.data.ChatMessage?, msg: com.jack.ai.data.ChatMessage): Boolean =
    msg.createdAt > 0 && (prev == null || msg.createdAt - prev.createdAt >= TIME_GAP_MS)

/** 时间分隔的字：今天「19:29」、昨天「昨天 19:29」、今年「11月3日 19:29」、更早「2025/11/3 19:29」。 */
internal fun stampWhen(at: Long, now: Long = System.currentTimeMillis()): String {
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
    val day = shortWhen(at, now)
    return if (day.contains(':')) hm else "$day $hm"
}

@Composable
private fun TimeStamp(at: Long) {
    Text(stampWhen(at), Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}
