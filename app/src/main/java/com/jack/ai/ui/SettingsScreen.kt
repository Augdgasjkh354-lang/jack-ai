package com.jack.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.jack.ai.data.MODEL_CHAT
import com.jack.ai.data.MODEL_REASONER
import com.jack.ai.data.Preset
import com.jack.ai.data.Settings
import com.jack.ai.data.ThemeMode
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
                Text(
                    "填好后，在聊天页打开「联网搜索」，AI 会在需要最新信息时自己去搜，并标出来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 模型 ----
            Section("默认模型") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.model == MODEL_CHAT,
                        onClick = { vm.updateSettings { it.copy(model = MODEL_CHAT) } },
                        label = { Text("普通对话") },
                    )
                    FilterChip(
                        selected = settings.model == MODEL_REASONER,
                        onClick = { vm.updateSettings { it.copy(model = MODEL_REASONER) } },
                        label = { Text("深度思考") },
                    )
                }
                Text(
                    "普通对话：快、便宜，适合日常聊天。深度思考：先想再答，适合数学、编程、复杂问题。聊天页输入框上方也能随时切换。",
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
                    "越低越严谨稳定（适合代码、数学），越高越有创意（适合写作、聊天）。默认 1.0。深度思考模式不受影响。",
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
