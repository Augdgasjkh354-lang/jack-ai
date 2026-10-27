package com.jack.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.api.ApiException
import com.jack.ai.data.Price
import com.jack.ai.data.Pricing
import com.jack.ai.data.Provider
import com.jack.ai.data.ProviderModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** 常见的 OpenAI 兼容服务，点一下填好名字和地址。 */
private val PROVIDER_PRESETS = listOf(
    "Kimi" to "https://api.moonshot.cn/v1",
    "通义千问" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "智谱" to "https://open.bigmodel.cn/api/paas/v4",
    "豆包" to "https://ark.cn-beijing.volces.com/api/v3",
    "硅基流动" to "https://api.siliconflow.cn/v1",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "OpenAI" to "https://api.openai.com/v1",
)

/** 设置页里的「其他模型服务」：列出已加的服务，可以添加、修改、删除。 */
@Composable
fun ProvidersSection(vm: ChatViewModel, providers: List<Provider>) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var deleting by remember { mutableStateOf<Provider?>(null) }
    val colors = MaterialTheme.colorScheme
    providers.forEach { p ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (p.apiKey.isBlank()) "还没填 Key · " else "") + (if (p.models.isEmpty()) "只用 Key（转文字、向量检索） · " else "${p.models.size} 个模型 · ") + p.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.apiKey.isBlank()) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BalanceButton(vm, p)
            TextButton(onClick = { deleting = p }) { Text("删除", color = colors.error) }
        }
    }
    OutlinedButton(onClick = { editing = Provider(name = "", baseUrl = "") }) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text("添加服务")
    }
    editing?.let { p ->
        ProviderDialog(vm, p, onDismiss = { editing = null }) { vm.saveProvider(it); editing = null }
    }
    deleting?.let { p ->
        ConfirmDeleteDialog("删除「${p.name}」？", "这个服务和它的模型会从列表里移除。", onDismiss = { deleting = null }) {
            vm.deleteProvider(p.id); deleting = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderDialog(vm: ChatViewModel, initial: Provider, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var workspaceId by remember { mutableStateOf(initial.workspaceId) }
    // 服务上全部模型的 id；已勾选的模型和它们的能力单独记
    var available by remember { mutableStateOf(initial.models.map { it.id }) }
    // 服务返回的价格（OpenRouter 才有）
    var prices by remember { mutableStateOf<Map<String, Price>>(emptyMap()) }
    var pricing by remember { mutableStateOf<ProviderModel?>(null) }
    val chosen = remember { mutableStateListOf<ProviderModel>().apply { addAll(initial.models) } }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }

    fun fetch() {
        loading = true
        error = null
        scope.launch {
            try {
                val (base, list) = vm.fetchModels(url, key, workspaceId)
                val ids = list.map { it.first }
                url = base
                available = ids
                prices = list.mapNotNull { (id, p) -> p?.let { id to it } }.toMap()
                // 已勾选但还没价格的，用服务给的价格补上
                chosen.forEachIndexed { i, m -> if (m.price == null) prices[m.id]?.let { chosen[i] = m.copy(price = it) } }
                if (ids.isEmpty()) error = "这个服务没有返回任何模型"
                // 原来勾选的模型服务上已经没有了，就去掉
                chosen.removeAll { it.id !in ids }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IOException) {
                error = "连不上这个地址：${e.message ?: "网络问题"}"
            } catch (e: Exception) {
                error = e.message ?: "获取失败"
            } finally {
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "添加模型服务" else "编辑「${initial.name}」") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial.name.isEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PROVIDER_PRESETS.forEach { (n, u) ->
                            AssistChip(onClick = { name = n; url = u }, label = { Text(n) })
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名字") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text("接口地址") },
                    placeholder = { Text("https://…/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, label = { Text("API Key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (runCatching { java.net.URI(url).host.equals("dashscope.aliyuncs.com", ignoreCase = true) }.getOrDefault(false)) {
                    OutlinedTextField(value = workspaceId, onValueChange = { workspaceId = it.trim() },
                        label = { Text("百炼业务空间 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("在百炼控制台的业务空间管理中复制业务空间 ID；获取模型列表和免费池探测需要。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { fetch() }, enabled = url.isNotBlank() && !loading) {
                        Text(if (available.isEmpty()) "获取模型列表" else "刷新模型列表")
                    }
                    if (loading) {
                        Spacer(Modifier.size(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                // 硅基流动的 Key 不选模型也有用：转文字、事实库的向量检索都借它
                if (chosen.isEmpty() && "siliconflow" in url.lowercase()) Text(
                    "只填 Key、不选模型也能保存：转文字和事实库的向量检索会用它。想拿它当聊天模型，再获取列表勾选。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                // 有的服务不提供模型列表，可以手动填模型名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = manual, onValueChange = { manual = it.trim() }, placeholder = { Text("手动填模型名") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = manual.isNotEmpty(), onClick = {
                        if (manual !in available) available = listOf(manual) + available
                        if (chosen.none { it.id == manual }) chosen.add(vm.guessModel(manual))
                        manual = ""
                    }) { Text("添加") }
                }
                if (available.isNotEmpty()) {
                    Text(
                        "已选 ${chosen.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (available.size > 8) {
                        OutlinedTextField(value = filter, onValueChange = { filter = it }, placeholder = { Text("搜索模型") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    // 已勾选的排前面
                    val shown = available.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }
                        .sortedBy { id -> if (chosen.any { it.id == id }) 0 else 1 }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(shown, key = { it }) { id ->
                            val m = chosen.firstOrNull { it.id == id }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (m != null) chosen.remove(m) else chosen.add(vm.guessModel(id, prices[id]))
                                    },
                                ) {
                                    Checkbox(checked = m != null, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                                    Text(id, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (m != null) {
                                    Row(Modifier.padding(start = 40.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        fun set(new: ProviderModel) { chosen[chosen.indexOf(m)] = new }
                                        FilterChip(selected = m.vision, onClick = { set(m.copy(vision = !m.vision)) }, label = { Text("能看图") })
                                        FilterChip(selected = m.reasoning, onClick = { set(m.copy(reasoning = !m.reasoning)) }, label = { Text("深度思考") })
                                        FilterChip(selected = m.price != null, onClick = { pricing = m },
                                            label = { Text(m.price?.let { "¥${short(it.miss)}/${short(it.out)}" } ?: "填价格") })
                                    }
                                }
                                HorizontalDivider(color = colors.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // 不选模型、只填 Key 也能存（转文字、向量检索只要 Key）
                enabled = name.isNotBlank() && url.isNotBlank() && (chosen.isNotEmpty() || key.isNotBlank()),
                onClick = {
                    // 保持服务上的顺序
                    val order = available.withIndex().associate { it.value to it.index }
                    onSave(initial.copy(name = name.trim(), baseUrl = url.trimEnd('/'), apiKey = key, workspaceId = workspaceId,
                        models = chosen.sortedBy { order[it.id] ?: Int.MAX_VALUE }))
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    pricing?.let { m ->
        PriceDialog(m.id, m.price, onDismiss = { pricing = null }) { p ->
            val i = chosen.indexOfFirst { it.id == m.id }
            if (i >= 0) chosen[i] = chosen[i].copy(price = p)
            pricing = null
        }
    }
}

private fun short(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

/** 手动填一个模型的价格（元 / 百万 token）。全部留空表示不计价。 */
@Composable
private fun PriceDialog(model: String, initial: Price?, onDismiss: () -> Unit, onSave: (Price?) -> Unit) {
    fun str(d: Double?) = d?.let { short(it) }.orEmpty()
    var miss by remember { mutableStateOf(str(initial?.miss)) }
    var hit by remember { mutableStateOf(str(initial?.hit)) }
    var out by remember { mutableStateOf(str(initial?.out)) }
    fun num(s: String) = s.toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("价格 · $model", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("元 / 百万 token，留空不计价",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    Triple("输入（未命中缓存）", miss) { v: String -> miss = v },
                    Triple("输入（缓存命中），不知道就留空", hit) { v: String -> hit = v },
                    Triple("输出", out) { v: String -> out = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(value = value, onValueChange = { set(it.filter { c -> c.isDigit() || c == '.' }) },
                        label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = num(miss)
                val o = num(out)
                onSave(if (m == null && o == null) null else Price(hit = num(hit) ?: m ?: 0.0, miss = m ?: 0.0, out = o ?: 0.0))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 支持查余额的服务（Kimi、硅基流动、OpenRouter）显示一个「余额」按钮，点了显示结果。 */
@Composable
private fun BalanceButton(vm: ChatViewModel, p: Provider) {
    val host = remember(p.baseUrl) { runCatching { java.net.URI(p.baseUrl).host.orEmpty() }.getOrDefault("") }
    if (p.apiKey.isBlank() || listOf("moonshot", "siliconflow", "openrouter", "deepseek").none { it in host }) return
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        result = "…"
        scope.launch { result = vm.providerBalance(p) ?: "不支持" }
    }) { Text(result ?: "余额") }
}

@Composable
fun FreePoolCard(settings: com.jack.ai.data.Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(com.jack.ai.data.FreePool.load(context)) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(settings.providers) {
        try { state = com.jack.ai.data.FreePool.probe(context, settings) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "探测失败：${e.message ?: "请稍后再试"}" }
    }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("免费额度池", style = MaterialTheme.typography.titleMedium)
            Text("百炼模型请开启「免费额度用完即停」，否则会扣费；硅基流动只选价格页标为免费的模型。", style = MaterialTheme.typography.bodySmall)
            val valid = state.models.filter { m -> settings.providers.any { it.id == m.providerId && it.apiKey.isNotBlank() && com.jack.ai.data.freePoolProvider(it.baseUrl) } }
            val available = valid.filter { it.exhaustedAt == 0L }
            val exhausted = valid.filter { it.exhaustedAt != 0L }
            Text("可用：" + available.joinToString("、") { it.model }.ifBlank { "无" })
            Text("已用完：" + exhausted.joinToString("、") { it.model }.ifBlank { "无" })
            val time = if (state.checkedAt == 0L) "尚未探测" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(state.checkedAt))
            Text("上次探测：$time", style = MaterialTheme.typography.bodySmall)
            (error ?: state.error.takeIf { it.isNotBlank() })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(enabled = !probing, onClick = {
                scope.launch {
                    probing = true; error = null
                    try { state = com.jack.ai.data.FreePool.probe(context, settings, force = true) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "探测失败：${e.message ?: "请稍后再试"}" }
                    finally { probing = false }
                }
            }) { Text(if (probing) "探测中…" else "重新探测") }
        }
    }
}
