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
import androidx.compose.animation.animateContentSize
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
import com.jack.ai.data.compressLimit
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
import com.jack.ai.receiveText
import com.jack.ai.consumeCameraRequest
import com.jack.ai.consumeVoiceRequest
import com.jack.ai.consumeRecordRequest
import com.jack.ai.recordingDone

// 聊天页底部的输入栏：输入框、附件、录音条、发送/停止按钮（从 ChatScreen.kt 拆出来）。

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
                Text((if (paused) "暂停 " else "录音 ") + com.jack.ai.data.media.AudioPcm.stamp(session.elapsed(now) / 1000),
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun InputBar(
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
    // 语音对话：同样先要麦克风权限
    val voiceChatPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceChat.start(ctx, vm) else Toast.makeText(ctx, "没有麦克风权限，没法语音对话。可以在系统设置里给 Kavi 打开", Toast.LENGTH_LONG).show()
    }
    fun startVoiceChat() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            VoiceChat.start(ctx, vm)
        else voiceChatPermission.launch(android.Manifest.permission.RECORD_AUDIO)
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
        VoiceChatBar()
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
            // 浮在消息上面的一个毛玻璃大胶囊：左边加号，中间打字，右边一个圆按钮（话筒 / 发送 / 停止）
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().glass(Radii.xl),
        ) {
            // 加了附件、打字换行时胶囊平滑地长高，不是一下子跳上去
            Column(Modifier.animateContentSize(androidx.compose.animation.core.spring(stiffness = 700f)).padding(6.dp)) {
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
                            MenuRow("图片、视频", icon = AppIcons.Image) {
                                attachMenu = false
                                try { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                            MenuRow("文件", icon = AppIcons.File) {
                                attachMenu = false
                                try { pickFiles.launch(arrayOf("*/*")) } catch (e: Exception) { Toast.makeText(ctx, "打不开：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                            MenuRow("语音对话", icon = AppIcons.Mic, enabled = !VoiceChat.on && com.jack.ai.RecordService.current == null) { attachMenu = false; startVoiceChat() }
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
                            if (settings.endpoint()?.reasoning != false) MenuRow(settings.thinking.labelFor(settings.endpoint()), icon = AppIcons.Brain, checked = settings.thinking != ThinkLevel.OFF) {
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
                                sub = "${conv.lastPromptTokens / 1000}k / ${settings.compressLimit() / 1000}k",
                                enabled = !state.streamingHere && state.compressingId == null,
                            ) { attachMenu = false; vm.compress() }
                        }
                        // 思考档位：从上面那一行点进来
                        RoundMenu(expanded = thinkMenu, onDismiss = { thinkMenu = false }) {
                            val ep = settings.endpoint()
                            ThinkLevel.entries.forEach { lv ->
                                MenuRow(lv.labelFor(ep), icon = AppIcons.Brain, checked = lv == settings.thinking) {
                                    vm.updateSettings { it.copy(thinking = lv) }; thinkMenu = false
                                }
                            }
                        }
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = field,
                        onValueChange = {
                            // 一下子贴进来一大段：存成附件，输入框保持原样（改消息时不算，附件跟不过去）
                            val pasted = if (editing == null) com.jack.ai.data.LongPaste.inserted(field.text, it.text) else null
                            if (pasted != null) vm.pasteAsFile(pasted)
                            else { field = it; if (it.text != text) onTextChange(it.text) }
                        },
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
                            // 点一下语音输入；长按开语音对话（说完自动发、读回答、接着听）
                            SendAction.MIC -> Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(42.dp)) {
                                Box(Modifier.fillMaxSize().combinedClickable(interactionSource = sendPress, indication = androidx.compose.foundation.LocalIndication.current,
                                    onClickLabel = "语音输入", onLongClickLabel = "语音对话",
                                    onClick = { haptics.tick(); startVoice() }, onLongClick = { haptics.tick(); startVoiceChat() }), contentAlignment = Alignment.Center) {
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
                                    color = if (canSend) colors.primary else colors.onSurface.copy(alpha = 0.08f),
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
    } else if (com.jack.ai.data.Attachments.isVideo(att.name) && att.path.isNotEmpty()) {
        Box {
            VideoThumb(att.path, 56.dp, 56.dp)
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
