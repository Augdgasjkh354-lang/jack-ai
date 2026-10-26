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
import com.jack.ai.data.GitHub
import com.jack.ai.data.Settings
import com.jack.ai.tools.ToolSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页的 GitHub：填令牌（只读或读写），测一下通不通。 */
@Composable
fun GitHubSection(vm: ChatViewModel, s: Settings) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf(s.githubToken) }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "不填也能搜和看公开项目（每小时 60 次）。要看你自己的私有项目、搜代码，填一个令牌：\n" +
            "1. 打开下面的链接，新建「Fine-grained token」，有效期自己定。\n" +
            "2. Repository access 选「All repositories」（或只选要给 Kavi 看的项目）。\n" +
            "3. Permissions：只让 Kavi 看，就把 Contents 设成「Read-only」；要让它改代码、推送、开 PR、处理 issue、看和重跑自动打包，" +
            "就把 Contents、Pull requests、Issues、Actions 都设成「Read and write」（Metadata 会自动只读）。\n" +
            "4. 生成后复制（github_pat_ 开头）填到下面。推送、合并、发评论之前 Kavi 都会先问你。令牌只存在手机上，不进备份。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    TextButton(onClick = { runCatching { uri.openUri("https://github.com/settings/personal-access-tokens/new") } }) { Text("打开 GitHub 令牌页") }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.trim()
            status = null
            vm.updateSettings { st -> st.copy(githubToken = input) }
        },
        placeholder = { Text("github_pat_...") },
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
                    runCatching { "通了：" + GitHub.me(input) }.getOrElse { e ->
                        "不通：" + if (e is Exception) ToolSession.errorText(e) else e.toString()
                    }
                }
                busy = false
            }
        }) { Text(if (busy) "测试中…" else "测试") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("不通")) colors.error else colors.onSurfaceVariant) }
}
