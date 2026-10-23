package com.jack.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Settings
import com.jack.ai.data.exaKeys
import com.jack.ai.data.tavilyKeys

/**
 * 设置页的「连接」：邮箱、GitHub、Notion、MCP 连接器收成一行一个，只显示连没连上；
 * 点哪个展开哪个（一次只开一个），连好之后就不占地方了。
 */
@Composable
fun ConnectionsSection(vm: ChatViewModel, s: Settings) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val mails = s.mails.count { it.ready }
    val mcp = s.mcpServers.count { it.enabled }
    val searchKeys = s.exaKeys.size + s.tavilyKeys.size
    val rows: List<Triple<String, Pair<String, Boolean>, @Composable () -> Unit>> = listOf(
        Triple("联网搜索", (if (searchKeys > 0) listOfNotNull(
            s.exaKeys.size.takeIf { it > 0 }?.let { "Exa $it 个" }, s.tavilyKeys.size.takeIf { it > 0 }?.let { "Tavily $it 个" },
        ).joinToString(" · ") else if (s.apiKey.isNotBlank()) "只用 DeepSeek 搜索" else "未设置") to (searchKeys > 0)) { SearchKeysSection(vm, s) },
        Triple("邮箱", (if (mails > 0) "已连接 $mails 个" else "未设置") to (mails > 0)) { MailSection(vm, s.mails) },
        Triple("GitHub", (if (s.githubToken.isNotBlank()) "已连接" else "未填令牌，只能看公开项目") to s.githubToken.isNotBlank()) { GitHubSection(vm, s) },
        Triple("Notion", (if (s.notionToken.isNotBlank()) "已连接" else "未设置") to s.notionToken.isNotBlank()) { NotionSection(vm, s) },
        Triple("微信消息", (if (s.wechatWatch) "交给 Kavi" else "关着") to s.wechatWatch) { WeChatSection(vm, s) },
        Triple("MCP 连接器", (if (mcp > 0) "$mcp 个开着" else "没有") to (mcp > 0)) { ConnectorsSection(vm, s.mcpServers) },
        Triple("Jack 专用代理（VPN）", (if (s.proxyEnabled) "开着" + s.proxyNode.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            else if (s.proxyNodes.isNotEmpty()) "关着" else "未设置") to s.proxyEnabled) { ProxySection(vm, s) },
    )
    val colors = MaterialTheme.colorScheme
    rows.forEachIndexed { i, (name, status, content) ->
        if (i > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
        val expanded = open == name
        Row(
            Modifier.fillMaxWidth().clickable { open = if (expanded) null else name }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (status.second) "✓ " else "") + status.first,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp).padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.second) colors.primary else colors.onSurfaceVariant,
            )
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "收起" else "展开",
                Modifier.padding(start = 4.dp), tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
