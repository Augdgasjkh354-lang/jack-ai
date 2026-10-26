package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Notion
import com.jack.ai.data.Settings
import com.jack.ai.tools.ToolSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页的 Notion：填内部集成的密钥，测一下通不通。 */
@Composable
fun NotionSection(vm: ChatViewModel, s: Settings) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf(s.notionToken) }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "让 Kavi 在你的 Notion 里记笔记、管任务。\n" +
            "1. 打开 Notion 集成页 → 新建「内部集成」，复制密钥（ntn_ 开头）填到下面。\n" +
            "2. 在 Notion 里打开要给 Kavi 用的页面或数据库 → 右上角 ··· → 连接 → 选这个集成。连父页面，下面的子页面也都能用。\n" +
            "Kavi 只能看到连接过的页面。密钥只存在手机上，不进备份。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    TextButton(onClick = { runCatching { uri.openUri("https://www.notion.so/profile/integrations") } }) { Text("打开 Notion 集成页") }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.trim()
            status = null
            vm.updateSettings { st -> st.copy(notionToken = input) }
        },
        placeholder = { Text("ntn_...") },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示") } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (input.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { "通了：" + Notion.me(input) }.getOrElse { e ->
                        "不通：" + if (e is Exception) ToolSession.errorText(e) else e.toString()
                    }
                }
                busy = false
            }
        }) { Text(if (busy) "测试中…" else "测试") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("不通")) colors.error else colors.onSurfaceVariant) }
}
