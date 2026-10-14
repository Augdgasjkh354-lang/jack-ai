package com.jack.ai.ui

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
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.MODEL_PRO
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.modelLabel
import com.jack.ai.data.Preset
import com.jack.ai.data.Settings
import com.jack.ai.data.ThemeMode
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Preset?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

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
                Text(
                    "没有 Key？到 platform.deepseek.com 注册、充值后，在「API keys」里创建一个，复制粘贴到这里。Key 只保存在这台手机上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // ---- Tavily ----
            Section("Tavily API Key（联网搜索用）") {
                var tavilyInput by remember { mutableStateOf(settings.tavilyKey) }
                var showTavily by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = tavilyInput,
                    onValueChange = {
                        tavilyInput = it.trim()
                        vm.updateSettings { s -> s.copy(tavilyKey = tavilyInput, webSearch = s.webSearch && tavilyInput.isNotBlank()) }
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
                Text(
                    "开着时，AI 会在需要最新信息时自己联网搜索、读网页，并标出来源；闲聊不会乱搜。Key 在 app.tavily.com 获取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 模型 ----
            Section("模型和思考") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MODEL_FLASH, MODEL_PRO).forEach { m ->
                        FilterChip(
                            selected = settings.model == m,
                            onClick = { vm.updateSettings { it.copy(model = m) } },
                            label = { Text(modelLabel(m)) },
                        )
                    }
                }
                Text(
                    "V4.1 Flash：快、便宜，能看图片。V4 Pro：更聪明，适合难题，但不能看图、价格约 4 倍。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThinkLevel.entries.forEach { lv ->
                        FilterChip(
                            selected = settings.thinking == lv,
                            onClick = { vm.updateSettings { it.copy(thinking = lv) } },
                            label = { Text(lv.label) },
                        )
                    }
                }
                Text(
                    "思考越深，回答越慢、越贵，但难题答得更好。聊天页输入框上方也能随时切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            // ---- 开发者提示词 ----
            Section("开发者提示词（最高优先级）") {
                SettingField("", settings.developerPrompt, "写在这里的规则对所有对话生效，优先级高于人设、项目说明和你发的消息。", minLines = 4) { v ->
                    vm.updateSettings { it.copy(developerPrompt = v) }
                }
                Text(
                    "适合放「必须遵守」的硬规则，比如：永远用中文回答；不确定的事要明说；回答末尾给出下一步建议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 创造力 ----
            Section("回答风格（温度 ${String.format(Locale.US, "%.1f", settings.temperature)}）") {
                Slider(
                    value = settings.temperature,
                    onValueChange = { v -> vm.updateSettings { it.copy(temperature = (v * 10).toInt() / 10f) } },
                    valueRange = 0f..1.5f,
                )
                Text(
                    "越低越严谨稳定，越高越有创意。默认 1.0。只在「不思考」时起作用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            }

            // ---- 人设 ----
            Section("人设（新对话开始时可选）") {
                settings.presets.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                p.prompt.ifBlank { "（无额外设定）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "编辑") }
                        IconButton(onClick = {
                            vm.updateSettings { s -> s.copy(presets = s.presets.filterNot { it.id == p.id }) }
                        }) { Icon(Icons.Default.Delete, "删除") }
                    }
                }
                OutlinedButton(onClick = { editing = Preset(name = "", prompt = "") }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("添加人设")
                }
            }

            // ---- 高级 ----
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

    editing?.let { p -> PresetDialog(p, onDismiss = { editing = null }) { updated ->
        vm.updateSettings { s ->
            val exists = s.presets.any { it.id == updated.id }
            s.copy(presets = if (exists) s.presets.map { if (it.id == updated.id) updated else it } else s.presets + updated)
        }
        editing = null
    } }

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

@Composable
private fun PresetDialog(preset: Preset, onDismiss: () -> Unit, onSave: (Preset) -> Unit) {
    var name by remember { mutableStateOf(preset.name) }
    var prompt by remember { mutableStateOf(preset.prompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preset.name.isEmpty()) "添加人设" else "编辑人设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名字") }, singleLine = true)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("设定（告诉 AI 它是谁、怎么回答）") },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(preset.copy(name = name.trim(), prompt = prompt.trim())) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
