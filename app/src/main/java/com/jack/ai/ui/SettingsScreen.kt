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
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            // ---- API Key ----
            Section("DeepSeek API Key") {
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
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
                    }) { Text("去获取 Key") }
                    OutlinedButton(onClick = {
                        balance = "查询中…"
                        scope.launch { balance = vm.checkBalance() }
                    }) { Text("查余额") }
                }
                balance?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Section("其他模型服务") {
                ProvidersSection(vm, settings.providers)
            }

            Section("邮箱") {
                MailSection(vm, settings.mails)
            }

            Section("连接器") {
                ConnectorsSection(vm, settings.mcpServers)
            }

            Section("GitHub") {
                GitHubSection(vm, settings)
            }

            Section("Notion") {
                NotionSection(vm, settings)
            }

            Section("Jack 专用代理") {
                ProxySection(vm, settings)
            }

            // ---- Tavily ----
            Section("Tavily API Key") {
                var tavilyInput by remember { mutableStateOf(settings.tavilyKey) }
                var showTavily by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = tavilyInput,
                    onValueChange = {
                        tavilyInput = it.trim()
                        vm.updateSettings { s -> s.copy(tavilyKey = tavilyInput, webSearch = s.webSearch && (tavilyInput.isNotBlank() || s.apiKey.isNotBlank())) }
                    },
                    placeholder = { Text("tvly-...") },
                    singleLine = true,
                    visualTransformation = if (showTavily) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showTavily = !showTavily }) { Text(if (showTavily) "隐藏" else "显示") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("联网搜索工具", Modifier.weight(1f))
                    Switch(
                        checked = settings.webSearch,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(webSearch = on) } },
                    )
                }
            }

            Section("技能") {
                OutlinedButton(onClick = onOpenSkills) { Text("管理技能") }
            }

            // ---- 模型 ----
            Section("模型和思考") {
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
            Section("统计") {
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

            // ---- 个性化 ----
            Section("个性化") {
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

            // ---- 长对话 ----
            Section("长对话自动压缩（${settings.compressTokens / 1000}k）") {
                Slider(
                    value = settings.compressTokens / 1000f,
                    onValueChange = { v -> vm.updateSettings { it.copy(compressTokens = ((v / 50).toInt().coerceAtLeast(1)) * 50_000) } },
                    valueRange = 50f..800f,
                    steps = 14,
                )
            }

            // ---- 创造力 ----
            Section("回答风格（温度 ${String.format(Locale.US, "%.1f", settings.temperature)}）") {
                Slider(
                    value = settings.temperature,
                    onValueChange = { v -> vm.updateSettings { it.copy(temperature = (v * 10).toInt() / 10f) } },
                    valueRange = 0f..1.5f,
                )
            }

            // ---- 外观 ----
            Section("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.themeMode == m,
                            onClick = { vm.updateSettings { it.copy(themeMode = m) } },
                            label = { Text(m.label) },
                        )
                    }
                }
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
            }

            // ---- 隐私和数据 ----
            Section("隐私和数据") {
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
                        exportLauncher.launch("JackAI备份-$date.zip")
                    }) { Text("备份") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("恢复")
                    }
                }
            }

            // ---- 高级 ----
            Section("版本更新") {
                UpdateSection(vm, settings)
            }

            Section("高级") {
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
                OutlinedButton(onClick = { confirmClear = true }) { Text("清空全部聊天记录") }
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
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
