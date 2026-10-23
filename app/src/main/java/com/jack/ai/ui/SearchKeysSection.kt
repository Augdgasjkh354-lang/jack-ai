package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.api.ExaClient
import com.jack.ai.api.KeyRest
import com.jack.ai.api.TavilyClient
import com.jack.ai.data.Settings
import com.jack.ai.data.exaKeys
import com.jack.ai.data.tavilyKeys
import kotlinx.coroutines.launch

/** 「连接 → 联网搜索」：Exa 和 Tavily 各能填好几个 Key，两家默认按 2:1 轮着用，同一家先用完第一个 Key 再换下一个，最后才用 DeepSeek。 */
@Composable
fun SearchKeysSection(vm: ChatViewModel, s: Settings) {
    val colors = MaterialTheme.colorScheme
    Text("默认轮着用：每三次搜索，两次先用 Exa、一次先用 Tavily；同一家填了好几个 Key 时，先把第一个用完再用下一个；找英文资料、论文、GitHub 时 Kavi 会指定 Exa，查最新新闻时指定 Tavily。一个 Key 额度用完、Key 不对或太频繁，就自动换下一个；都不行再用 DeepSeek。",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    KeyList("Exa", "每月 1 号补满 10 美元免费额度，大约一两千次搜索。exa.ai 注册拿 Key", s.exaKeys,
        onChange = { keys -> vm.updateSettings { it.copy(exaKey = keys.joinToString("\n")) } },
        test = { k -> ExaClient().search(k, "DeepSeek", 1).size })
    KeyList("Tavily", "每月有免费额度。tavily.com 注册拿 Key", s.tavilyKeys,
        onChange = { keys -> vm.updateSettings { it.copy(tavilyKey = keys.joinToString("\n")) } },
        test = { k -> TavilyClient().search(k, "DeepSeek", 1).size })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("联网搜索工具", Modifier.weight(1f))
        Switch(checked = s.webSearch, onCheckedChange = { on -> vm.updateSettings { it.copy(webSearch = on) } })
    }
}

@Composable
private fun KeyList(name: String, hint: String, keys: List<String>, onChange: (List<String>) -> Unit, test: suspend (String) -> Int) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val results = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$name（${keys.size} 个）", style = MaterialTheme.typography.labelLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        keys.forEachIndexed { i, k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 只露头尾几位，认得出是哪个就行
                    Text("${i + 1}. ${k.take(4)}…${k.takeLast(4)}", style = MaterialTheme.typography.bodyMedium)
                    val state = results[k] ?: if (KeyRest.resting(k)) "这个 Key 暂时歇着（额度用完、Key 不对或太频繁），先用别的" else null
                    state?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) colors.primary else colors.error) }
                }
                TextButton(onClick = {
                    results[k] = "测试中…"
                    scope.launch {
                        results[k] = runCatching { test(k) }.fold({ "✓ 能用" }, { e -> "不通：" + (e.message ?: "出错了") })
                    }
                }) { Text("测试") }
                TextButton(onClick = { onChange(keys - k) }) { Text("删除", color = colors.error) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.trim() },
                placeholder = { Text("再加一个 $name Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = input.length >= 8 && input !in keys, onClick = { onChange(keys + input); input = "" }) { Text("添加") }
        }
    }
}
