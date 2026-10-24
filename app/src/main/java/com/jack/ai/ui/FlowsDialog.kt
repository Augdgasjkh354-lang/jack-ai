package com.jack.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.ChatViewModel
import com.jack.ai.data.BrowserFlow
import com.jack.ai.data.BrowserFlows
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 技能 → 浏览器流程：看存好的流程，改、删、自己写一个。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowsDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var flows by remember { mutableStateOf<List<BrowserFlow>>(emptyList()) }
    var editing by remember { mutableStateOf<BrowserFlow?>(null) }
    var deleting by remember { mutableStateOf<BrowserFlow?>(null) }
    LaunchedEffect(Unit) { flows = vm.loadFlows() }
    fun save(list: List<BrowserFlow>) { flows = list; scope.launch { vm.saveFlows(list) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("浏览器流程") },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                actions = { IconButton(onClick = { editing = BrowserFlow(name = "", site = "", steps = "") }) { Icon(Icons.Default.Add, "写一个流程") } },
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "在某个网站上办一件事的做法。Kavi 做成一个浏览器任务后，你说「存成流程」它就会整理好给你确认；" +
                            "下次打开那个网站时它会想起来，照着步骤做。平时不占上下文。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (flows.isEmpty()) item { Text("还没有流程。", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(flows, key = { it.id }) { f ->
                    Card(Modifier.fillMaxWidth().clickable { editing = f }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(f.name, style = MaterialTheme.typography.titleSmall)
                            Text(f.site + (f.whenToUse.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (f.uses > 0) "用过 ${f.uses} 次，上次 " + SimpleDateFormat("M月d日", Locale.CHINA).format(Date(f.usedAt)) else "还没用过",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    editing?.let { f ->
        var name by remember(f.id) { mutableStateOf(f.name) }
        var site by remember(f.id) { mutableStateOf(f.site) }
        var whenToUse by remember(f.id) { mutableStateOf(f.whenToUse) }
        var steps by remember(f.id) { mutableStateOf(f.steps) }
        val isNew = flows.none { it.id == f.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "写一个流程" else "编辑流程") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, placeholder = { Text("比如：1688 比价") })
                    OutlinedTextField(site, { site = it }, label = { Text("网站") }, singleLine = true, placeholder = { Text("比如：1688.com") })
                    OutlinedTextField(whenToUse, { whenToUse = it }, label = { Text("什么时候用") }, placeholder = { Text("比如：要在 1688 上找货比价时") }, maxLines = 3)
                    OutlinedTextField(steps, { steps = it }, label = { Text("步骤") }, minLines = 6, maxLines = 16,
                        placeholder = { Text("1. 打开 https://s.1688.com 搜 {关键词}\n2. 点「销量」排序\n3. 用 extract 读前 {页数} 页的名称、价格、店铺，存成 CSV") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && BrowserFlows.siteOf(site).isNotEmpty() && steps.isNotBlank(), onClick = {
                    val updated = f.copy(name = name.trim(), site = BrowserFlows.siteOf(site), whenToUse = whenToUse.trim(), steps = steps.trim())
                    save(if (isNew) flows + updated else flows.map { if (it.id == f.id) updated else it })
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (!isNew) TextButton(onClick = { deleting = f; editing = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }

    deleting?.let { f ->
        ConfirmDeleteDialog("删除流程？", "「${f.name}」会被删除。", onDismiss = { deleting = null }) {
            save(flows.filterNot { it.id == f.id })
            deleting = null
        }
    }
}
