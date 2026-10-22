package com.jack.ai.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conv = state.current
    val project = state.project(state.currentProjectId)
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
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
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            containerColor = bg,
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
                                conv?.takeIf { it.messages.isNotEmpty() }?.title ?: "Jack AI",
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
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
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
                InputBar(
                    vm = vm,
                    state = state,
                    text = text,
                    onTextChange = { text = it },
                    onNeedTavily = {
                        Toast.makeText(ctx, "请先在设置里填写 Tavily API Key", Toast.LENGTH_LONG).show()
                        onOpenSettings()
                    },
                )
            },
        ) { padding ->
            // 消息铺满整屏，从上下两个半透明的框后面滚过去
            Box(Modifier.fillMaxSize().swipeToOpen { scope.launch { drawerState.open() } }) {
                if (conv == null || conv.messages.isEmpty()) {
                    Box(Modifier.padding(padding)) { EmptyState(vm, state, conv, onOpenMemory, onSuggest = { text = it }) }
                } else {
                    MessageList(vm, conv, state.streaming, padding)
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

private data class Suggestion(val title: String, val prompt: String)

private val SUGGESTIONS = listOf(
    Suggestion("写一份周报", "帮我写一份本周工作周报，做成 Word 文档。我这周做了："),
    Suggestion("做个演示文稿", "帮我做一个关于「」的 PPT，大概 8 页。"),
    Suggestion("今天有什么新闻", "今天有什么值得关注的新闻？"),
    Suggestion("整理成表格", "帮我把下面的内容整理成 Excel 表格：\n"),
)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModeSwitch(work = conv?.work ?: state.draftWork, onChange = vm::setWork)
        Spacer(Modifier.height(24.dp))
        LogoMark(52.dp)
        Spacer(Modifier.height(20.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？" else "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (conv?.work ?: state.draftWork) {
            WorkTemplates(vm, state.settings.workTemplates, onSuggest)
        } else SUGGESTIONS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { sg ->
                    Surface(
                        onClick = { onSuggest(sg.prompt) },
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceContainerLowest,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(sg.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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
private fun MessageList(vm: ChatViewModel, conv: Conversation, streaming: Boolean, bars: PaddingValues) {
    val listState = rememberLazyListState()
    val reversed = conv.messages.asReversed()
    val lastId = conv.messages.last().id
    // 倒序排列：最新的消息在最下面，流式输出时自动贴底
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = bars.calculateTopPadding() + 12.dp, bottom = bars.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        reversed.forEach { msg ->
            if (msg.id == conv.summaryUntilId) {
                item(key = "summary-divider") { SummaryDivider(conv.summary) }
            }
            item(key = msg.id) {
                Box(Modifier.animateItem()) {
                    if (msg.role == "user") {
                        UserBubble(msg, onDelete = { vm.deleteFrom(msg.id) }, onEdit = { vm.editMessage(msg.id, it) })
                    } else {
                        val isLast = msg.id == lastId
                        AssistantMessage(
                            msg = msg,
                            streaming = streaming && isLast,
                            isLast = isLast,
                            onRegenerate = vm::regenerate,
                            onDelete = { vm.deleteFrom(msg.id) },
                            onReview = vm::reviewLast,
                            onContinue = if (conv.work && isLast && !msg.truncated && vm.canContinue(msg)) vm::continueWork else null,
                            onContinueWriting = vm::continueWriting,
                            onReport = { note -> vm.reportIssue(msg.id, note) },
                            onFixApp = { text -> vm.send(text) },
                        )
                    }
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

@Composable
private fun InputBar(
    vm: ChatViewModel,
    state: UiState,
    text: String,
    onTextChange: (String) -> Unit,
    onNeedTavily: () -> Unit,
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
                                .clickable { onTextChange(withMention(text, name)); mentionPick = null }
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
        Surface(
            shape = RoundedCornerShape(26.dp),
            // 输入框半透明，能隐约看到后面的消息
            color = (if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest).copy(alpha = 0.82f),
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
                if (state.pending.isNotEmpty() || state.importing) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        items(state.pending, key = { it.id }) { att -> PendingChip(att) { vm.removePending(att.id) } }
                        if (state.importing) {
                            item { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("问点什么…", color = colors.outline) },
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
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        Surface(
                            onClick = { attachMenu = true },
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.padding(start = 6.dp).size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, "更多", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
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
                                    enabled = !state.streaming && state.compressingId == null,
                                    onClick = {
                                        attachMenu = false
                                        vm.compress()
                                    },
                                )
                            }
                        }
                    }
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
                        active = settings.webSearch && (settings.tavilyKey.isNotBlank() || settings.apiKey.isNotBlank()),
                        icon = AppIcons.Globe,
                        onClick = {
                            if (settings.tavilyKey.isBlank() && settings.apiKey.isBlank()) onNeedTavily()
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
                    if (state.streaming && text.isNotBlank()) {
                        // 回答进行中也能发：作为插话，下一步就会看到
                        Surface(onClick = { if (vm.send(text)) onTextChange("") }, shape = CircleShape, color = colors.primary,
                            modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUp, "插话", Modifier.size(20.dp), tint = colors.onPrimary)
                            }
                        }
                    }
                    if (state.streaming) {
                        Surface(onClick = vm::stop, shape = CircleShape, color = colors.onSurface, modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Stop, "停止", Modifier.size(16.dp), tint = colors.surface)
                            }
                        }
                    } else if (text.isBlank() && state.pending.isEmpty()) {
                        Surface(onClick = { startVoice() }, shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Mic, "语音输入", Modifier.size(20.dp), tint = colors.onPrimaryContainer)
                            }
                        }
                    } else {
                        val canSend = (text.isNotBlank() || state.pending.isNotEmpty()) && !state.importing
                        Surface(
                            onClick = { if (vm.send(text)) onTextChange("") },
                            enabled = canSend,
                            shape = CircleShape,
                            color = if (canSend) colors.primary else colors.surfaceVariant,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUp, "发送", Modifier.size(20.dp),
                                    tint = if (canSend) colors.onPrimary else colors.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                        color = if (on) colors.onSurface else colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(18.dp))
                        .background(colors.surfaceContainerLowest)
                        .combinedClickable(
                            onClick = { if (t == null) editing = com.jack.ai.data.Preset(name = "", prompt = "") else onUse(t.prompt) },
                            onLongClick = { if (t != null) editing = t },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(t?.name ?: "＋ 新模板", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (t != null) FontWeight.Medium else null,
                        color = if (t != null) colors.onSurface else colors.onSurfaceVariant)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
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
