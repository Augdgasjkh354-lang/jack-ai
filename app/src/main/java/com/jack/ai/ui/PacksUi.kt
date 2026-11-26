package com.jack.ai.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Settings
import com.jack.ai.data.packs.AppPackHost
import com.jack.ai.data.packs.InstalledPack
import com.jack.ai.data.packs.PackError
import com.jack.ai.data.packs.PackFormat
import com.jack.ai.data.packs.PackManifest
import com.jack.ai.data.packs.PackRun
import com.jack.ai.data.packs.PackRuntime
import com.jack.ai.data.packs.PackToolSpec
import com.jack.ai.data.packs.Packs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/*
 * 技能页里的「工具包」：卡片、装工具包（文件 / GitHub / 示例）、装之前的权限确认页、详情页（配置、试一下、更新、删除）。
 */

/** 工具包这一块的状态：正在看哪个、正在确认装哪个、在忙什么。 */
@Stable
class PacksUi {
    var choosing by mutableStateOf(false)
    var staged by mutableStateOf<Packs.Staged?>(null)
    var openId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var problems by mutableStateOf<List<String>?>(null)
    /** 点了「试试示例」：加一，装示例的那段看到变了就去准备。 */
    var sampleAsk by mutableStateOf(0)
}

// ---------- 卡片 ----------

/** 工具包的图标：带了 icon.png 就用它，没带就是名字的头一个字压在一块彩色圆角方块上。 */
@Composable
fun PackAvatar(icon: File?, name: String, size: Dp = 44.dp) {
    val bmp = remember(icon?.path, icon?.lastModified()) { icon?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        return
    }
    val hue = packHue(name)
    Box(Modifier.size(size).clip(shape).background(hue.wash), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).ifBlank { "?" }, color = hue.color, fontWeight = FontWeight.Bold,
            style = if (size >= 60.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun packHue(name: String): Hue {
    val pool = listOf(Hue.BLUE, Hue.VIOLET, Hue.GREEN, Hue.ORANGE, Hue.AMBER, Hue.SKY, Hue.RED)
    return pool[Math.floorMod(name.hashCode(), pool.size)]
}

/** 技能页上的一张：图标、名字和版本、哪来的、一句话、要了哪些权限，右边开关。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackCard(p: InstalledPack, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val m = p.manifest
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = Radii.l) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
            PackAvatar(remember(p.updatedAt) { Packs.icon(ctx, p.id) }, m.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text("v${m.version}", style = MaterialTheme.typography.labelSmall.tabular, color = colors.outline)
                }
                Text(Packs.sourceLabel(p) + if (m.author.isNotBlank()) " · ${m.author}" else "", style = MaterialTheme.typography.labelSmall,
                    color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("${m.tools.size} 个工具", Hue.GRAY)
                    shortPerms(m).forEach { (t, h) -> Tag(t, h) }
                }
                if (p.missing.isNotEmpty()) Text("先填：" + p.missing.joinToString("、") { it.label.ifBlank { it.key } } + "，填好才能用",
                    style = MaterialTheme.typography.labelSmall, color = colors.error, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(8.dp))
            Switch(p.enabled, onToggle)
        }
    }
}

/** 卡片上那排小标签：只写最要紧的。 */
private fun shortPerms(m: PackManifest): List<Pair<String, Hue>> = buildList {
    val p = m.permissions
    if (p.net.isNotEmpty()) add("🌐 ${if (p.net.size == 1) p.net[0].removePrefix("*.") else "${p.net.size} 个网站"}" to Hue.BLUE)
    if (p.files == "workspace") add("📂 对话文件夹" to Hue.ORANGE) else if (p.files == "pack") add("📁 自己的文件" to Hue.GREEN)
    if (p.notify) add("🔔 通知" to Hue.AMBER)
    if (p.ai) add("✦ 模型" to Hue.VIOLET)
}

@Composable
private fun Tag(text: String, hue: Hue) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = hue.color, maxLines = 1,
        modifier = Modifier.clip(Radii.xs).background(hue.wash).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** 还没装过工具包时的那张：说明是什么，给一个能马上试的示例。 */
@Composable
fun PacksEmptyCard(onSample: () -> Unit, onInstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(Hue.VIOLET.wash).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌘", color = Hue.VIOLET.color, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text("给 Kavi 装新本事", style = MaterialTheme.typography.titleMedium)
        }
        Text("工具包是一小段 JavaScript 加一份说明，装上以后 Kavi 多出几个工具（查快递、查天气、连你自己的服务……）。" +
            "它只能用说明里声明过的权限，装之前会给你看清楚。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = onSample, shape = androidx.compose.foundation.shape.CircleShape) { Text("试试示例：天气") }
            OutlinedButton(onClick = onInstall) { Text("装一个") }
        }
    }
}

// ---------- 装 ----------

/** 装工具包的入口（底部面板）和后面的确认、出错提示、详情页，都挂在技能页上。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDialogs(ui: PacksUi, vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun stage(label: String, work: suspend () -> Packs.Staged) {
        ui.busy = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work() } }
            ui.busy = null
            r.onSuccess { ui.choosing = false; ui.staged = it }
                .onFailure { e -> ui.problems = (e as? PackError)?.problems ?: listOf(e.message ?: e.javaClass.simpleName) }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stage("正在解开…") {
            // 压缩包开头是 PK；不是的当成 Operit 的 .js 脚本
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw PackError(listOf("打不开这个文件"))
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name ?: "script.js"
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) Packs.stageZip(ctx, bytes.inputStream())
            else {
                val text = bytes.toString(Charsets.UTF_8)
                if (!com.jack.ai.data.packs.OperitImport.looksLikeOperit(text)) throw PackError(listOf("这不是工具包：要么是有 kavi-pack.json 的 .zip，要么是开头有 METADATA 的 Operit 脚本（.js）"))
                com.jack.ai.data.packs.OperitImport.stage(ctx, text, name)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(ui.sampleAsk) {
        if (ui.sampleAsk > 0) stage("正在准备…") { Packs.stageAsset(ctx, "weather") }
    }

    if (ui.choosing) {
        var github by remember { mutableStateOf("") }
        var showGit by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { if (ui.busy == null) ui.choosing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = Radii.sheetTop, containerColor = raisedColor) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("装工具包", style = MaterialTheme.typography.titleLarge)
                Text("装之前会先给你看它要哪些权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp))
                SourceRow("📦", Hue.BLUE, "从文件选", "一个 .zip（里面有 kavi-pack.json），或者 Operit 的 .js 脚本") { pick.launch(arrayOf("*/*")) }
                SourceRow("⌥", Hue.GRAY, "从 GitHub 装", "填「作者/项目」，记下版本，以后能检查更新") { showGit = !showGit }
                AnimatedVisibility(showGit) {
                    Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(github, { github = it }, Modifier.weight(1f), singleLine = true, shape = Radii.m,
                            placeholder = { Text("作者/项目、作者/项目/文件夹，或某个 .js") })
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = Packs.parseSpec(github) != null && ui.busy == null,
                            onClick = { stage("正在从 GitHub 下载…") {
                                // 指向一个 .js 的，当成 Operit 脚本转过来
                                if (github.substringBefore('@').trimEnd('/').endsWith(".js")) {
                                    val (text, name) = Packs.githubFile(settings.githubToken, github)
                                    com.jack.ai.data.packs.OperitImport.stage(ctx, text, name)
                                } else Packs.stageGitHub(ctx, settings.githubToken, github)
                            } }) { Text("装") }
                    }
                }
                SourceRow("☀", Hue.AMBER, "示例：天气", "查任意城市的天气，演示联网和存储（Open-Meteo，免费、不用 Key）") {
                    stage("正在准备…") { Packs.stageAsset(ctx, "weather") }
                }
                ui.busy?.let { b ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(b, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    ui.problems?.let { list ->
        AlertDialog(
            onDismissRequest = { ui.problems = null },
            title = { Text("装不了") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { ui.problems = null }) { Text("知道了") } },
        )
    }

    ui.staged?.let { s ->
        val old = settings.packs.firstOrNull { it.id == s.manifest.id }
        PackConfirm(s, old,
            onCancel = { Packs.discard(s); ui.staged = null },
            onInstall = {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { Packs.install(ctx, s, old) }
                    vm.updateSettings { st -> st.copy(packs = st.packs.filterNot { it.id == p.id } + p) }
                    ui.staged = null
                    Toast.makeText(ctx, if (old != null) "已更新到 v${p.manifest.version}" else "装好了，Kavi 现在能用「${p.manifest.name}」", Toast.LENGTH_SHORT).show()
                    // 有要填的就直接打开详情页去填
                    if (p.missing.isNotEmpty()) ui.openId = p.id
                }
            })
    }

    ui.openId?.let { id ->
        settings.packs.firstOrNull { it.id == id }?.let { p -> PackDetail(p, settings, vm, ui) { ui.openId = null } } ?: run { ui.openId = null }
    }
}

@Composable
private fun SourceRow(glyph: String, hue: Hue, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Radii.m).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(Radii.s).background(hue.wash), contentAlignment = Alignment.Center) {
            Text(glyph, color = hue.color, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- 全屏页的骨架 ----------

/** 截图测试里不套 Dialog（Dialog 是另一个窗口，截不到）。 */
internal var packPagesInline = false

@Composable
private fun FullPage(onBack: () -> Unit, title: String, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (packPagesInline) { PageBody(onBack, title, bottom, content); return }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onBack)
        PageBody(onBack, title, bottom, content)
    }
}

@Composable
private fun PageBody(onBack: () -> Unit, title: String, bottom: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
                if (bottom != null) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { bottom() }
            }
        }
}

/** 页头：大图标、名字、版本和作者、哪来的、一句话。 */
@Composable
private fun PackHero(icon: File?, m: PackManifest, source: String, extra: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PackAvatar(icon, m.name, 76.dp)
        Spacer(Modifier.height(12.dp))
        Text(m.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull("v${m.version}", m.author.takeIf { it.isNotBlank() }, source).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.tabular, color = colors.onSurfaceVariant)
        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
            modifier = Modifier.padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        extra?.invoke()
    }
}

/** 一块带标题的分组：浅底大圆角。 */
@Composable
private fun Group(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun PermRows(m: PackManifest) {
    Group("它要这些权限", "没列出来的一律碰不到：你的聊天、记忆、别的文件和别的工具包") {
        PackFormat.permLines(m).forEach { l ->
            val hue = if (l.risky) Hue.ORANGE else when (l.glyph) { "🌐" -> Hue.BLUE; "📁" -> Hue.GREEN; "🔔" -> Hue.AMBER; "✦" -> Hue.VIOLET; "✓" -> Hue.GREEN; else -> Hue.GRAY }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(Radii.s).background(hue.wash), contentAlignment = Alignment.Center) {
                    Text(l.glyph, color = hue.color, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (l.risky) hue.color else MaterialTheme.colorScheme.onSurface)
                    Text(l.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToolRows(m: PackManifest, onTry: ((PackToolSpec) -> Unit)? = null) {
    Group("Kavi 会多出这些工具", if (onTry != null) "点一个可以自己填参数试一下" else null) {
        m.tools.forEach { t ->
            Row(Modifier.fillMaxWidth().then(if (onTry != null) Modifier.clickable { onTry(t) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont), fontWeight = FontWeight.Bold)
                    Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.parameters.isNotEmpty()) Text("参数：" + t.parameters.entries.joinToString("、") { (k, p) -> k + if (p.required) "（必填）" else "" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                }
                if (onTry != null) Text("试一下", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun Readme(raw: String, name: String) {
    // 开头那行「# 名字」和页头重复，去掉
    val text = raw.trimStart().let { t -> if (t.lineSequence().firstOrNull()?.trim()?.removePrefix("#")?.trim() == name) t.substringAfter('\n').trimStart() else t }
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Group("说明") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Box(Modifier.heightIn(max = if (open) 4000.dp else 160.dp)) {
                MarkdownText(text, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant, textSizeSp = 14f)
            }
            if (text.length > 300) TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "展开全部") }
        }
    }
}

/** 代码：装之前能看一眼（默认露前 12 行）。 */
@Composable
private fun CodePeek(f: java.io.File) {
    val code = remember(f.path) { runCatching { f.readText() }.getOrNull() } ?: return
    val lines = code.lines()
    var open by remember { mutableStateOf(false) }
    Group("代码", "${f.name} · ${lines.size} 行") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text((if (open) lines.take(2000) else lines.take(12)).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont), softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (lines.size > 12) TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "看全部 ${lines.size} 行") }
        }
    }
}

// ---------- 确认页 ----------

/** 装之前的那一页：是谁、要什么权限、多出哪些工具。更新时标出多要了什么。 */
@Composable
internal fun PackConfirm(s: Packs.Staged, old: InstalledPack?, onCancel: () -> Unit, onInstall: () -> Unit, byKavi: Boolean = false) {
    val m = s.manifest
    val widened = old?.let { PackFormat.widened(it.manifest, m) }.orEmpty()
    val source = when {
        s.source.startsWith(Packs.GITHUB) -> "GitHub · " + s.source.removePrefix(Packs.GITHUB).substringBefore('@')
        s.source == "sample" -> "App 自带的示例"
        s.source == "kavi" -> "Kavi 刚写的"
        s.source.startsWith("operit") -> "从 Operit 脚本转来的"
        else -> "从文件"
    }
    var installing by remember { mutableStateOf(false) }
    FullPage(onBack = onCancel, title = when {
        byKavi && old != null -> "Kavi 改好了工具包"
        byKavi -> "Kavi 做好了一个工具包"
        old != null -> "更新工具包"
        else -> "装工具包"
    }, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(50.dp)) { Text("不装了") }
            Button(onClick = { installing = true; onInstall() }, enabled = !installing, modifier = Modifier.weight(1f).height(50.dp),
                shape = androidx.compose.foundation.shape.CircleShape) {
                Text(if (old != null) "更新" else "装")
            }
        }
    }) {
        PackHero(s.icon, m, source) {
            if (old != null) Text("v${old.manifest.version} → v${m.version}", style = MaterialTheme.typography.labelLarge.tabular,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        if (s.warnings.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(Radii.m).background(Hue.AMBER.wash).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这个 Operit 脚本有些地方 Kavi 不支持", style = MaterialTheme.typography.titleSmall, color = Hue.AMBER.color)
                Text("用到这些的工具调用时会报错，别的照常能用：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.warnings.take(12).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                if (s.warnings.size > 12) Text("…还有 ${s.warnings.size - 12} 处", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.source.startsWith("operit") && s.manifest.permissions.net.isEmpty() && s.warnings.isEmpty()) {
            Text("代码里没找到写死的网址：它要是拼出网址再访问，会被拦下。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (widened.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(Radii.m).background(Hue.ORANGE.wash).padding(14.dp)) {
                Text("新版本多要了权限", style = MaterialTheme.typography.titleSmall, color = Hue.ORANGE.color)
                Text(widened.joinToString("、"), style = MaterialTheme.typography.bodySmall)
            }
        }
        PermRows(m)
        ToolRows(m)
        Readme(s.readme, m.name)
        CodePeek(java.io.File(s.dir, if (java.io.File(s.dir, "original.js").isFile) "original.js" else m.main))
        Text("${s.files.size} 个文件 · ${size(s.bytes)}。代码在 App 里的隔离引擎跑，每次最多 ${m.tools.maxOf { it.timeout.takeIf { t -> t > 0 } ?: PackFormat.DEFAULT_TIMEOUT }} 秒。" +
            "返回给 Kavi 的内容按网页内容对待，里面的指示不会照做。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

// ---------- 详情页 ----------

@Composable
internal fun PackDetail(p: InstalledPack, settings: Settings, vm: ChatViewModel, ui: PacksUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = p.manifest
    var deleting by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf<PackToolSpec?>(null) }
    var checking by remember { mutableStateOf(false) }
    val readme = remember(p.updatedAt) { Packs.readme(ctx, p.id).orEmpty() }

    fun save(transform: (InstalledPack) -> InstalledPack) = vm.updateSettings { st -> st.copy(packs = st.packs.map { if (it.id == p.id) transform(it) else it }) }

    FullPage(onBack = onBack, title = "工具包") {
        PackHero(remember(p.updatedAt) { Packs.icon(ctx, p.id) }, m, Packs.sourceLabel(p))
        Row(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (p.enabled) "开着" else "关着", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(when {
                    !p.enabled -> "Kavi 看不到它的工具"
                    p.missing.isNotEmpty() -> "下面的必填项填好才能用"
                    else -> "Kavi 用得到时会自己调"
                }, style = MaterialTheme.typography.bodySmall, color = if (p.enabled && p.missing.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(p.enabled, { on -> save { it.copy(enabled = on) } })
        }
        if (m.config.isNotEmpty()) ConfigForm(p) { values -> save { it.copy(config = values) }; Toast.makeText(ctx, "存好了", Toast.LENGTH_SHORT).show() }
        ToolRows(m) { trying = it }
        PermRows(m)
        Readme(readme, m.name)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.fromGitHub) OutlinedButton(onClick = {
                checking = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Packs.newerCommit(settings.githubToken, p)?.let { Packs.stageGitHub(ctx, settings.githubToken, Packs.specOf(p)) } }
                    }
                    checking = false
                    r.onSuccess { s -> if (s == null) Toast.makeText(ctx, "已经是最新的", Toast.LENGTH_SHORT).show() else { onBack(); ui.staged = s } }
                        .onFailure { e -> ui.problems = (e as? PackError)?.problems ?: listOf(e.message ?: "检查不了") }
                }
            }, enabled = !checking, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (checking) "正在检查…" else "检查更新") }
            OutlinedButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除这个工具包") }
            Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).let { f ->
                "装于 ${f.format(java.util.Date(p.installedAt))}" + if (p.updatedAt != p.installedAt) " · 更新于 ${f.format(java.util.Date(p.updatedAt))}" else ""
            } + if (p.commit.isNotBlank()) " · 提交 ${p.commit.take(7)}" else "",
                style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    trying?.let { t -> TryTool(p, t, settings) { trying = null } }
    if (deleting) ConfirmDeleteDialog("删除「${m.name}」？", "它的代码、存的数据和你填的配置都会删掉。", onDismiss = { deleting = false }) {
        deleting = false
        scope.launch {
            withContext(Dispatchers.IO) { Packs.remove(ctx, p.id) }
            vm.updateSettings { st -> st.copy(packs = st.packs.filterNot { it.id == p.id }) }
            onBack()
        }
    }
}

/** 要用户填的那些：带密钥的打码，可以点眼睛看。 */
@Composable
private fun ConfigForm(p: InstalledPack, onSave: (Map<String, String>) -> Unit) {
    val values = remember(p.id) { mutableStateMapOf<String, String>().apply { putAll(p.config) } }
    val shown = remember(p.id) { mutableStateMapOf<String, Boolean>() }
    val changed = p.manifest.config.any { (values[it.key] ?: "") != (p.config[it.key] ?: "") }
    Group("要你填的", "存在手机上（加密），只有这个工具包能读到") {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.manifest.config.forEach { c ->
                val secret = c.secret && shown[c.key] != true
                OutlinedTextField(values[c.key].orEmpty(), { values[c.key] = it }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m,
                    label = { Text(c.label.ifBlank { c.key } + if (c.required) " *" else "") },
                    placeholder = c.hint.takeIf { it.isNotBlank() }?.let { h -> { Text(h) } },
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (c.secret) ({ TextButton(onClick = { shown[c.key] = shown[c.key] != true }) { Text(if (secret) "看" else "藏") } }) else null)
            }
            Button(onClick = { onSave(values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }) }, enabled = changed,
                shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.align(Alignment.End)) { Text("保存") }
        }
    }
}

// ---------- 试一下 ----------

/** 自己填参数跑一次工具，看它返回什么（不经过模型，不花钱）。 */
@Composable
private fun TryTool(p: InstalledPack, t: PackToolSpec, settings: Settings, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val values = remember(t.name) { mutableStateMapOf<String, String>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PackRun?>(null) }
    val ready = t.parameters.none { (k, v) -> v.required && values[k].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(t.name, style = MaterialTheme.typography.titleMedium.copy(fontFamily = CodeFont)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                t.parameters.forEach { (k, param) ->
                    OutlinedTextField(values[k].orEmpty(), { values[k] = it }, Modifier.fillMaxWidth(), singleLine = param.type != "object" && param.type != "array",
                        shape = Radii.m, label = { Text(k + if (param.required) " *" else "") },
                        supportingText = param.description.takeIf { it.isNotBlank() }?.let { d -> { Text(d + if (param.enum.isNotEmpty()) "（${param.enum.joinToString(" / ")}）" else "") } })
                }
                if (running) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("在跑…", style = MaterialTheme.typography.bodySmall)
                }
                result?.let { r ->
                    val hue = if (r.ok) Hue.GREEN else Hue.RED
                    Column(Modifier.fillMaxWidth().clip(Radii.m).background(hue.wash).padding(12.dp)) {
                        Text((if (r.ok) "成功" else "没成") + " · ${r.ms} 毫秒", style = MaterialTheme.typography.labelLarge.tabular, color = hue.color)
                        SelectionContainer {
                            Text(r.text.take(4000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont), modifier = Modifier.padding(top = 6.dp))
                        }
                        if (r.logs.isNotEmpty()) Text("日志：\n" + r.logs.takeLast(10).joinToString("\n"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !running, onClick = {
                running = true; result = null
                scope.launch {
                    val args = buildJsonObject {
                        t.parameters.forEach { (k, param) -> values[k]?.takeIf { it.isNotBlank() }?.let { v -> put(k, typed(param.type, v)) } }
                    }
                    val code = withContext(Dispatchers.IO) { Packs.code(ctx, p) }
                    result = if (code == null) PackRun(false, "代码文件不见了，重新装一下", emptyList(), 0)
                    else PackRuntime.run(p.manifest, code, t.name, args, p.config, AppPackHost(ctx, settings, p, workspace = null, callTool = null))
                    running = false
                }
            }) { Text(if (result == null) "跑一下" else "再跑一次") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("关掉") } },
    )
}

/** 界面上填的都是字：按参数类型转一下（数字、真假、JSON）。 */
private fun typed(type: String, v: String): kotlinx.serialization.json.JsonElement = when (type) {
    "number", "integer" -> v.toDoubleOrNull()?.let { d -> if (type == "integer" || d % 1.0 == 0.0) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: JsonPrimitive(v)
    "boolean" -> JsonPrimitive(v.trim() in setOf("true", "是", "1", "对"))
    "object", "array" -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(v) }.getOrElse { JsonPrimitive(v) }
    else -> JsonPrimitive(v)
}

private fun size(n: Long) = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
}

/** Kavi 用 install_pack 交过来的工具包：弹同一页权限确认，用户的选择交回给工具（真正的安装由工具做）。 */
@Composable
fun PackInstallHost() {
    val req by com.jack.ai.data.packs.PackInstallPrompt.pending.collectAsState()
    req?.let { r -> PackConfirm(r.staged, r.old, onCancel = { r.reply(false) }, onInstall = { r.reply(true) }, byKavi = true) }
}
