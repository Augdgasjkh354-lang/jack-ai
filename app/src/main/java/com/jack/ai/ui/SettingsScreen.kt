package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import com.jack.ai.data.hasAnyKey
import com.jack.ai.data.phoneDataOn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.DEFAULT_BASE_URL
import com.jack.ai.data.COMPRESS_MAX
import com.jack.ai.data.COMPRESS_MIN
import kotlin.math.roundToInt
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.modelChoices
import com.jack.ai.data.costYuan
import androidx.compose.runtime.produceState
import com.jack.ai.data.Settings
import com.jack.ai.data.ThemeMode
import com.jack.ai.data.fullAuth
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    settings: Settings,
    onBack: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenUsage: () -> Unit,
    canLock: () -> Boolean,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportBackup(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    // null 是首页（分组列表）；还没填任何 Key 时直接打开 Key 那页
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (settings.hasAnyKey()) null else SettingsPage.KEY) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val homeScroll = rememberScrollState()
    val pageScroll = remember(page) { androidx.compose.foundation.ScrollState(0) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Scaffold(
        // 浅灰的底，上面一张张白卡片
        containerColor = if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "设置", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = {
                    IconButton(onClick = { if (page != null) page = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(if (page == null) homeScroll else pageScroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (page == null) SettingsHome(vm, settings, onPick = { page = it }, onOpenSkills = onOpenSkills, onOpenUsage = onOpenUsage)
            // ---- 个性化 ----
            if (page == SettingsPage.PROFILE) Section {
                SettingField("怎么称呼你", settings.userName, "例如：Jack") { v ->
                    vm.updateSettings { it.copy(userName = v) }
                }
                SettingField("关于你", settings.aboutMe, "职业、兴趣、所在城市、正在做的事…AI 会参考这些来回答", minLines = 3) { v ->
                    vm.updateSettings { it.copy(aboutMe = v) }
                }
                SettingField("希望 AI 怎么回答", settings.replyStyle, "例如：像朋友一样聊天，轻松一点；正经做事时简洁、先给结论", minLines = 3) { v ->
                    vm.updateSettings { it.copy(replyStyle = v) }
                }
            }

            // ---- API Key ----
            if (page == SettingsPage.KEY) Section {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it.trim()
                        vm.updateSettings { s -> s.copy(apiKey = keyInput) }
                    },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        Links.open(ctx, "https://platform.deepseek.com/api_keys")
                    }) { Text("去获取 Key") }
                    OutlinedButton(onClick = {
                        balance = "查询中…"
                        scope.launch { balance = vm.checkBalance() }
                    }) { Text("查余额") }
                }
                balance?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            if (page == SettingsPage.PROVIDERS) Section {
                ProvidersSection(vm, settings.providers)
                FreePoolCard(settings)
            }

            if (page == SettingsPage.LOCAL) Section(hint = "装在手机上的向量模型：按意思找记忆、查事实库，判断问题难不难。不联网、不花钱；默认在硅基流动没配或连不上时用。") {
                LocalModelsSection(vm, settings)
            }

            if (page == SettingsPage.REMOTE) Section(hint = "在 iPhone、电脑上通过你自己的 ChatGPT 站点用这台手机上的 Kavi：记忆、文件、邮件、浏览器都在手机上。") {
                RemoteSection(vm, settings)
            }

            if (page == SettingsPage.CONNECT) Section(hint = "点开一项设置；连好之后只显示一行状态。") {
                ConnectionsSection(vm, settings)
            }

            if (page == SettingsPage.PHONE) Section(hint = "让 Kavi 替你在别的 App 里点、输入、翻页（比如在微信里找聊天记录、在淘宝里搜东西）。只能操作你勾过的 App。") {
                PhoneControlSection(vm, settings)
            }

            if (page == SettingsPage.ASK) Section(hint = "随时叫出 Kavi，带上当前屏幕问：「这是什么」「帮我回这条」「这个值不值」。") {
                AskScreenSection(vm, settings)
            }

            if (page == SettingsPage.PHONEDATA) Section(hint = "每样单独开，默认都关着。") {
                PhoneDataSection(vm, settings)
            }

            if (page == SettingsPage.FOLDERS) Section(hint = "授权给 Kavi 的文件夹：它能在里面找文件、拷进对话处理、把做好的文件存回去，搬和删之前先问你。别的地方它碰不到。") {
                PhoneFoldersSection(vm, settings)
            }

            // ---- 模型 ----
            if (page == SettingsPage.MODEL) Section {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.modelChoices().forEach { c ->
                        FilterChip(
                            selected = settings.model == c.key,
                            onClick = { vm.updateSettings { it.copy(model = c.key) } },
                            label = { Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThinkLevel.entries.forEach { lv ->
                        FilterChip(
                            selected = settings.thinking == lv,
                            onClick = { vm.updateSettings { it.copy(thinking = lv) } },
                            label = { Text(lv.label) },
                        )
                    }
                }
                // 思考摘要：思考时一行行小标题；回答出来后展开还是折叠
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("思考摘要")
                        Text("思考时把每段思考压成一行小标题显示出来。另请后台模型，每段花一点点钱",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.thinkSummary, onCheckedChange = { on -> vm.updateSettings { it.copy(thinkSummary = on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("回答出来后折叠思考摘要")
                        Text("关着时摘要一直展开在回答上面；原始思考总是收进「深度思考」",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.summaryCollapsed, onCheckedChange = { on -> vm.updateSettings { it.copy(summaryCollapsed = on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("空白页显示写文案的快捷入口")
                        Text("新对话里点一下就开始：发朋友圈、写小红书、抖音脚本、回客户、产品介绍、报价单",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.bizStarts, onCheckedChange = { on -> vm.updateSettings { it.copy(bizStarts = on) } })
                }
            }

            // ---- 长对话 ----
            if (page == SettingsPage.COMPRESS) Section(hint = "对话用到这么多上下文时，较早的部分自动压缩成提要。越高记得越全、每次越贵；模型本身的上限比它低时，以模型为准。") {
                // 松手才生效：在设置页里上下翻时误碰一下不会改掉
                var draft by remember(settings.compressTokens) { mutableStateOf(settings.compressTokens / 1000f) }
                Text("${draft.toInt()}k", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = draft,
                    onValueChange = { draft = (it / 50).roundToInt() * 50f },
                    onValueChangeFinished = { vm.updateSettings { s -> s.copy(compressTokens = draft.toInt() * 1000) } },
                    valueRange = COMPRESS_MIN / 1000f..COMPRESS_MAX / 1000f,
                    steps = (COMPRESS_MAX - COMPRESS_MIN) / 50_000 - 1,
                )
            }

            // ---- 外观 ----
            if (page == SettingsPage.LOOK) Section {
                ThemeTiles(settings.themeMode) { m -> vm.updateSettings { it.copy(themeMode = m) } }
                Text("桌面图标", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon.OPTIONS.forEach { o ->
                        val on = settings.appIcon == o.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).clip(Radii.m)
                                .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    Radii.m)
                                .clickable { if (!on) vm.updateSettings { it.copy(appIcon = o.id) } }
                                .padding(vertical = 10.dp),
                        ) {
                            AppIconTile(o.id, 44.dp)
                            Text(o.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                    }
                }
                Text("换了以后，桌面上的图标要过几秒才变；有的手机会把它挪到最后一页，或者要重新拖回原来的位置。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("主题色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENTS.forEach { a ->
                        val sel = settings.accent == a.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { vm.updateSettings { it.copy(accent = a.id) } },
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(a.light.first))
                                    .then(
                                        if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface,
                                            androidx.compose.foundation.shape.CircleShape) else Modifier,
                                    ),
                            )
                            Text(a.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text("字体大小（${(settings.fontScale * 100).toInt()}%）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                var scale by remember { mutableStateOf(settings.fontScale) }
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { vm.updateSettings { s -> s.copy(fontScale = scale) } },
                    valueRange = 0.85f..1.3f,
                    steps = 8,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("链接用 Kavi 浏览器打开")
                        Text(
                            if (settings.linksInApp) "回答和设置里的网页链接在 Kavi 浏览器里打开" else "网页链接交给手机的系统浏览器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.linksInApp, onCheckedChange = { on -> vm.updateSettings { it.copy(linksInApp = on) } })
                }
            }

            // ---- 隐私和数据 ----
            if (page == SettingsPage.PRIVACY) Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("应用锁")
                    }
                    Switch(
                        checked = settings.appLock,
                        onCheckedChange = { on ->
                            if (on && !canLock()) {
                                Toast.makeText(ctx, "请先在手机设置里设置锁屏密码或指纹", Toast.LENGTH_LONG).show()
                            } else {
                                vm.updateSettings { it.copy(appLock = on) }
                            }
                        },
                    )
                }
                FullAuthRow(vm, settings)
                AllowRulesRow(vm, settings)
                MemoryResetRow(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val date = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
                        exportLauncher.launch("Kavi备份-$date.zip")
                    }) { Text("备份") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("恢复")
                    }
                }
                Text(
                    "换手机时，系统自带的迁移不会带走 Kavi 的设置。用这里的「备份」导出、在新手机上「恢复」，聊天和普通设置都会回来；Key、令牌、邮箱授权码不在备份里，要重新填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 高级 ----
            if (page == SettingsPage.UPDATE) Section {
                UpdateSection(vm, settings)
            }

            if (page == SettingsPage.ADVANCED) Section {
                var url by remember { mutableStateOf(settings.baseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it.trim()
                        vm.updateSettings { s -> s.copy(baseUrl = url.ifBlank { DEFAULT_BASE_URL }) }
                    },
                    label = { Text("接口地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "一般不用改。默认 $DEFAULT_BASE_URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("清空全部聊天记录") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("从备份恢复？") },
            text = { Text("现在手机上的聊天记录、项目、记忆和设置会被备份里的内容替换。") },
            confirmButton = {
                TextButton(onClick = { vm.importBackup(uri); confirmRestore = null }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部聊天记录？") },
            text = { Text("所有对话都会被永久删除，无法恢复。") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = if (label.isNotEmpty()) ({ Text(label) }) else null,
        placeholder = { Text(placeholder) },
        singleLine = minLines == 1,
        minLines = minLines,
        maxLines = if (minLines == 1) 1 else 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 子页里的一组设置：一张白卡片装内容，说明写在卡片下面（标题在顶栏上）。 */
@Composable
private fun Section(hint: String? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.Surface(
            shape = Radii.l,
            color = if (dark) colors.surfaceContainer else colors.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
        }
    }
}

/** 外观里的主题：三个小预览块（浅色、深色、跟随系统），点哪个用哪个。 */
@Composable
private fun ThemeTiles(selected: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeMode.entries.forEach { m ->
            val on = m == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clip(Radii.m)
                    .border(if (on) 2.dp else 1.dp, if (on) colors.onSurface else colors.outlineVariant, Radii.m)
                    .clickable { onPick(m) }.padding(10.dp),
            ) {
                // 小预览：一张「页面」上两道「文字」
                val light = androidx.compose.ui.graphics.Color(0xFFF3F4F6) to androidx.compose.ui.graphics.Color(0xFFD1D5DB)
                val darkC = androidx.compose.ui.graphics.Color(0xFF1D212B) to androidx.compose.ui.graphics.Color(0xFF3A4150)
                Row(Modifier.fillMaxWidth().height(44.dp).clip(Radii.xs)) {
                    listOfNotNull(if (m != ThemeMode.DARK) light else null, if (m != ThemeMode.LIGHT) darkC else null).forEach { (bg, line) ->
                        Column(Modifier.weight(1f).fillMaxSize().background(bg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(line))
                            Box(Modifier.fillMaxWidth(0.55f).height(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(line))
                        }
                    }
                }
                Text(m.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 完全授权：开着时发邮件、删邮件、删表格这类操作不再弹窗确认。可以开 1 小时，到点自动关。 */
@Composable
private fun FullAuthRow(vm: ChatViewModel, settings: Settings) {
    // 到点时刷新一下，让开关自己变回关
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(settings.fullAuthUntil, tick) {
        val wait = settings.fullAuthUntil - System.currentTimeMillis()
        if (wait > 0 && settings.fullAuthUntil != Long.MAX_VALUE) {
            kotlinx.coroutines.delay(wait + 500)
            tick++
        }
    }
    val on = tick >= 0 && settings.fullAuth
    var asking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("完全授权")
            Text(
                when {
                    !on -> "发邮件、删除等操作前弹窗确认"
                    settings.fullAuthUntil == Long.MAX_VALUE -> "已开启：所有操作不再确认，直接执行"
                    else -> "已开启，" + java.text.SimpleDateFormat("HH:mm", Locale.CHINA).format(java.util.Date(settings.fullAuthUntil)) + " 自动关闭"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { want ->
                if (want) asking = true else vm.updateSettings { it.copy(fullAuthUntil = 0) }
            },
        )
    }
    if (asking) {
        val grant = { until: Long -> vm.updateSettings { it.copy(fullAuthUntil = until) }; asking = false }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("开启完全授权？") },
            text = {
                Text("开启后，发邮件、删除和移动邮件、清空已删除、删除表格这些操作都不再弹窗确认，AI 会直接执行，定时任务在后台也能做。\n\n" +
                    "风险：AI 会读你的邮件，如果有人在邮件里藏了指令，可能骗它去删信、移信，没有弹窗替你拦着。" +
                    "只有一种情况仍会弹窗：读过邮件或网页之后，发信给你从没发过的地址。建议只在需要时打开，用完就关。")
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { grant(Long.MAX_VALUE) }) { Text("一直开着") }
                    TextButton(onClick = { grant(System.currentTimeMillis() + 3_600_000L) }) { Text("开 1 小时") }
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}

/** 设置里的一页：首页上的一行，点进去是原来那一块设置。 */
private enum class SettingsPage(val title: String) {
    PROFILE("个性化"), KEY("DeepSeek API Key"), PROVIDERS("其他模型服务"), MODEL("模型和思考"), LOCAL("本地模型"),
    COMPRESS("长对话自动压缩"), CONNECT("连接"), REMOTE("远程连接"), PHONE("操作其他 App"), ASK("问这一屏"),
    PHONEDATA("手机数据"), FOLDERS("手机文件夹"), LOOK("外观"), PRIVACY("隐私和数据"), UPDATE("版本更新"), ADVANCED("高级"),
}

/** 首页上的一行：图标、名字、右边灰字的当前状态（warn 时标红），点了进子页或跳到别的页面。 */
private class SettingsEntry(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val value: String? = null,
    val warn: Boolean = false,
    val onClick: () -> Unit,
)

/** 设置首页：像 ChatGPT 那样分几组，每组一张圆角卡片，一行一个入口。 */
@Composable
private fun SettingsHome(
    vm: ChatViewModel,
    settings: Settings,
    onPick: (SettingsPage) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val monthSpent by produceState<Double?>(null) {
        val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
        value = vm.loadUsage().filter { it.date.startsWith(month) }.sumOf { it.costYuan() ?: 0.0 }
    }
    fun go(p: SettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, warn: Boolean = false) =
        SettingsEntry(icon, p.title, value, warn) { onPick(p) }
    val noKey = !settings.hasAnyKey()
    val model = settings.modelChoices().firstOrNull { it.key == settings.model }?.label
    SettingsGroup("关于你", Hue.BLUE, listOf(
        go(SettingsPage.PROFILE, Icons.Default.Person, settings.userName.ifBlank { "未设置" }),
    ))
    SettingsGroup("模型", Hue.VIOLET, listOf(
        go(SettingsPage.KEY, AppIcons.Lock, if (settings.apiKey.isNotBlank()) "已填" else "未填", warn = noKey),
        go(SettingsPage.PROVIDERS, AppIcons.Code, if (settings.providers.isEmpty()) "未添加" else "${settings.providers.size} 个"),
        go(SettingsPage.MODEL, AppIcons.Brain, listOfNotNull(model, settings.thinking.label).joinToString(" · ")),
        go(SettingsPage.LOCAL, AppIcons.Chip),
        go(SettingsPage.COMPRESS, AppIcons.Compress, "${settings.compressTokens / 1000}k"),
    ))
    SettingsGroup("Kavi 能用的", Hue.GREEN, listOf(
        go(SettingsPage.CONNECT, AppIcons.Globe),
        SettingsEntry(Icons.Default.Build, "技能", onClick = onOpenSkills),
        go(SettingsPage.REMOTE, AppIcons.Share),
    ))
    SettingsGroup("手机", Hue.AMBER, listOf(
        go(SettingsPage.PHONE, AppIcons.Phone, if (settings.phoneApps.isEmpty()) "未开启" else "${settings.phoneApps.size} 个 App"),
        go(SettingsPage.ASK, Icons.Default.Search),
        go(SettingsPage.PHONEDATA, Icons.AutoMirrored.Filled.List, if (settings.phoneDataOn) "已开" else "全关"),
        go(SettingsPage.FOLDERS, AppIcons.Folder, if (settings.phoneFolders.isEmpty()) "未授权" else "${settings.phoneFolders.size} 个"),
    ))
    SettingsGroup("应用", Hue.GRAY, listOf(
        go(SettingsPage.LOOK, AppIcons.Moon, settings.themeMode.label),
        SettingsEntry(Icons.Default.Info, "统计", monthSpent?.let { "本月 " + yuan(it) }, onClick = onOpenUsage),
        go(SettingsPage.PRIVACY, Icons.Default.Lock, when {
            settings.fullAuth -> "完全授权开着"
            settings.appLock -> "应用锁开着"
            else -> null
        }, warn = settings.fullAuth),
        go(SettingsPage.UPDATE, AppIcons.Download),
        go(SettingsPage.ADVANCED, Icons.Default.Settings),
    ))
}

/** 一组入口：灰色小标题，下面一张圆角卡片，行和行之间一道从文字起头的细线。图标放在浅色小方块里，一组一个颜色，好找。 */
@Composable
private fun SettingsGroup(title: String, hue: Hue, entries: List<SettingsEntry>) {
    val tint = hue.color
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        androidx.compose.material3.Surface(
            shape = Radii.l,
            color = if (dark) colors.surfaceContainer else colors.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth().hairline(Radii.l),
        ) {
            Column {
                entries.forEachIndexed { i, e ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 62.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Box(Modifier.size(32.dp).clip(Radii.s).background(hue.wash),
                            contentAlignment = Alignment.Center) {
                            Icon(e.icon, null, Modifier.size(18.dp), tint = tint)
                        }
                        Spacer(Modifier.size(14.dp))
                        Text(e.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.value?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (e.warn) colors.error else colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 170.dp))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


/** 隐私和数据里的「重置记忆」：清掉关于你的，留下做事的经验；7 天内能恢复。 */
@Composable
private fun MemoryResetRow(vm: ChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var asking by remember { mutableStateOf(false) }
    var withChat by remember { mutableStateOf(false) }
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // 重置、恢复是在后台做的：做完记忆列表会变，跟着它重查一次
    val memories = vm.state.collectAsState().value.memories
    val backup by androidx.compose.runtime.produceState<com.jack.ai.data.MemoryReset.Backup?>(null, refresh, memories) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.MemoryReset.latest(ctx) }
    }
    val day = remember { java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("重置记忆")
                Text("忘掉关于你的一切，从头认识你；浏览器、手机操作的经验都留着。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { withChat = false; asking = true }) { Text("重置") }
        }
        backup?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${day.format(java.util.Date(b.at))} 重置过，${day.format(java.util.Date(b.expiresAt))} 前能恢复", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.restoreMemory(); refresh++ }) { Text("恢复") }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("重置 Kavi 的记忆？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会清掉：关于你的记忆（常记、深处、作废的、历史版本）、心里挂着的事、最近聊过什么的经历、夜间整理记录。")
                    Text("会留着：浏览器流程和手机流程、网站和 App 备忘、技能、事实库、做事经验，聊天记录也不删。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(withChat, { withChat = it })
                        Text("各个对话、项目里的记忆也一起清", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("清掉的先存 ${com.jack.ai.data.MemoryReset.KEEP_DAYS} 天，这期间能在这里恢复（会换掉重置后新记下的），之后自动删掉。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { vm.resetMemory(withChat); asking = false; refresh++ }) { Text("重置", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}


/** 确认卡片上点过「以后都允许」的那几类事：列出来，点「撤销」以后又会问。 */
@Composable
private fun AllowRulesRow(vm: ChatViewModel, settings: com.jack.ai.data.Settings) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("以后都允许的事")
        if (settings.alwaysAllow.isEmpty()) Text("还没有。Kavi 问「要不要发送、提交」时点「以后都允许」，同一类事以后就不再问（花钱的、删除的不在此列）。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        settings.alwaysAllow.sortedByDescending { it.at }.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.revokeAllow(r.key) }) { Text("撤销") }
            }
        }
    }
}
