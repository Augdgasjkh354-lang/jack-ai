package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Scaffold(
        // 浅灰的底，上面一张张白卡片
        containerColor = if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 个性化 ----
            Section("个性化", icon = Icons.Default.Person) {
                SettingField("怎么称呼你", settings.userName, "例如：Jack") { v ->
                    vm.updateSettings { it.copy(userName = v) }
                }
                SettingField("关于你", settings.aboutMe, "职业、兴趣、所在城市、正在做的事…AI 会参考这些来回答", minLines = 3) { v ->
                    vm.updateSettings { it.copy(aboutMe = v) }
                }
                SettingField("希望 AI 怎么回答", settings.replyStyle, "例如：简洁直接、先给结论、用中文、像朋友一样说话", minLines = 3) { v ->
                    vm.updateSettings { it.copy(replyStyle = v) }
                }
            }

            // ---- API Key ----
            Section("DeepSeek API Key", icon = AppIcons.Lock) {
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

            Section("其他模型服务", icon = AppIcons.Code) {
                ProvidersSection(vm, settings.providers)
            }

            Section("连接", icon = AppIcons.Share, hint = "点开一项设置；连好之后只显示一行状态。") {
                ConnectionsSection(vm, settings)
            }

            Section("技能", icon = Icons.Default.Build) {
                OutlinedButton(onClick = onOpenSkills) { Text("管理技能") }
            }

            Section("手机文件夹", icon = AppIcons.Folder, hint = "授权给 Kavi 的文件夹：它能在里面找文件、拷进对话处理、把做好的文件存回去，搬和删之前先问你。别的地方它碰不到。") {
                PhoneFoldersSection(vm, settings)
            }

            // ---- 模型 ----
            Section("模型和思考", icon = AppIcons.Brain) {
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
            }

            // ---- 统计 ----
            // ---- 长对话 ----
            Section("长对话自动压缩", icon = AppIcons.Compress, hint = "对话用到这么多上下文时，较早的部分自动压缩成提要。越高记得越全、每次越贵；模型本身的上限比它低时，以模型为准。") {
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

            Section("统计", icon = Icons.Default.Info) {
                val monthSpent by produceState<Double?>(null) {
                    val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
                    value = vm.loadUsage().filter { it.date.startsWith(month) }.sumOf { it.costYuan() ?: 0.0 }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本月已花 " + (monthSpent?.let { yuan(it) } ?: "…") +
                            if (settings.monthlyBudget > 0) " / 预算 ${yuan(settings.monthlyBudget)}" else "")
                    }
                    OutlinedButton(onClick = onOpenUsage) { Text("查看") }
                }
            }


            // ---- 外观 ----
            Section("外观", icon = AppIcons.Image) {
                ThemeTiles(settings.themeMode) { m -> vm.updateSettings { it.copy(themeMode = m) } }
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
            Section("隐私和数据", icon = AppIcons.Lock) {
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
            Section("版本更新", icon = AppIcons.Download) {
                UpdateSection(vm, settings)
            }

            Section("高级", icon = Icons.Default.Settings) {
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

/** 一组设置：一张白卡片，左上角图标加粗体标题，下面是这一组的内容；说明写在卡片下面。 */
@Composable
private fun Section(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, hint: String? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = if (dark) colors.surfaceContainer else colors.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    if (icon != null) {
                        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurface)
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
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
                modifier = Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .border(if (on) 2.dp else 1.dp, if (on) colors.onSurface else colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable { onPick(m) }.padding(10.dp),
            ) {
                // 小预览：一张「页面」上两道「文字」
                val light = androidx.compose.ui.graphics.Color(0xFFF3F4F6) to androidx.compose.ui.graphics.Color(0xFFD1D5DB)
                val darkC = androidx.compose.ui.graphics.Color(0xFF1D212B) to androidx.compose.ui.graphics.Color(0xFF3A4150)
                Row(Modifier.fillMaxWidth().height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))) {
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
