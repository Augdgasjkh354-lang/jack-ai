package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Mcp
import com.jack.ai.data.McpServer
import kotlinx.coroutines.launch

/**
 * 设置页里的连接器（MCP 服务）：填网址（和 Key）连上后，它的工具 AI 就能直接用。
 * 每行一个：名字、有几个工具、开关、修改、删除。
 */
@Composable
fun ConnectorsSection(vm: ChatViewModel, servers: List<McpServer>) {
    var editing by remember { mutableStateOf<McpServer?>(null) }
    val colors = MaterialTheme.colorScheme
    if (servers.isEmpty()) {
        Text(
            "连上别人做好的服务（MCP），AI 就能用它的工具，比如地图、天气、快递、股票。服务商会给你一个网址，有的还要 Key。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    servers.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (s.tools.isEmpty()) "没有工具" else "${s.tools.size} 个工具：" + s.tools.joinToString("、") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(s.enabled, { on -> vm.setConnector(s.copy(enabled = on)) })
            TextButton(onClick = { editing = s }) { Text("修改") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = McpServer(name = "", url = "") }) { Text("添加连接器") }
        // 常用的一键添加：网址填好，只差 Key
        Mcp.PRESETS.filter { p -> servers.none { it.url == p.url } }.forEach { p ->
            OutlinedButton(onClick = { editing = McpServer(name = p.name, url = p.url) }) { Text("+ ${p.name}") }
        }
    }

    editing?.let { s ->
        ConnectorDialog(vm, s, isNew = servers.none { it.id == s.id }, onDone = { editing = null })
    }
}

@Composable
private fun ConnectorDialog(vm: ChatViewModel, server: McpServer, isNew: Boolean, onDone: () -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var key by remember { mutableStateOf(server.key) }
    var askFirst by remember { mutableStateOf(server.askFirst) }
    var route by remember { mutableStateOf(server.route) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(if (isNew) "添加连接器" else "修改连接器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it.trim() }, label = { Text("网址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/mcp 或 …/sse") })
                OutlinedTextField(key, { key = it }, label = { Text("Key（没有就空着）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(
                    Mcp.PRESETS.firstOrNull { it.url == url }?.keyHelp
                        ?: "Key 一般直接粘贴；服务要求特殊请求头时写成「名字: 值」；要放进网址的，在网址里写 {key}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每次调用都先问我", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(askFirst, { askFirst = it })
                }
                Text(
                    "关着时，服务标明会改动数据的工具仍然会先问你；只查询的工具直接用，定时任务和监控也能用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 网络路线：按规则（国内直连、国外走 Kavi 专用代理）、直连、走代理
                Text("网络", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "自动", "direct" to "直连", "proxy" to "走代理").forEach { (k, label) ->
                        androidx.compose.material3.FilterChip(route == k, { route = k }, label = { Text(label) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) { Text("删除这个连接器", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && url.startsWith("http") && (Mcp.KEY_SLOT !in url || key.isNotBlank()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val r = vm.saveConnector(server.copy(name = name.trim(), url = url.trim(), key = key.trim(), askFirst = askFirst, route = route))
                        busy = false
                        r.onSuccess { onDone() }.onFailure { error = "连不上：" + (it.message ?: it.javaClass.simpleName) }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("连接并保存")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("取消") } },
    )
    if (confirmDelete) {
        ConfirmDeleteDialog("删除连接器？", "「${server.name}」会被删掉，AI 就用不了它的工具了。", onDismiss = { confirmDelete = false }) {
            vm.deleteConnector(server.id)
            confirmDelete = false
            onDone()
        }
    }
}
