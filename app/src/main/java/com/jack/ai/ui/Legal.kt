package com.jack.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 用户协议和隐私政策（正文在 assets/legal/，上架前要填好开发者名称、邮箱这些）。
 * 正式版第一次打开先弹同意页：同意之前不建主界面、不联网、不申请任何权限（国内商店审核必查）。
 * 协议有重大修改时把 [VERSION] 加一，用户下次打开会重新确认。
 */
object Legal {
    const val VERSION = 1
    private const val PREFS = "legal"
    private const val KEY = "agreed_version"

    enum class Doc(val title: String, val asset: String) {
        TERMS("用户协议", "legal/terms.md"),
        PRIVACY("隐私政策", "legal/privacy.md"),
    }

    /** 这一版需不需要先同意：只有正式版要。 */
    fun mustAsk(ctx: Context): Boolean = com.jack.ai.Edition.STORE && !agreed(ctx)

    fun agreed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) >= VERSION

    fun agree(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, VERSION).putLong("agreed_at", System.currentTimeMillis()).apply()
    }

    fun text(ctx: Context, doc: Doc): String =
        runCatching { ctx.assets.open(doc.asset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("（没读到${doc.title}）")
}

/** 第一次打开的同意页：讲清几件最要紧的事，能点开全文；不同意再确认一次，仍不同意就退出。 */
@Composable
fun ConsentScreen(onAgree: () -> Unit, onExit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var reading by rememberSaveable { mutableStateOf<Legal.Doc?>(null) }
    var sure by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("欢迎使用 Kavi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Text("使用前请仔细阅读《用户协议》和《隐私政策》。几件最要紧的事：", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            listOf(
                "Kavi 没有自己的服务器，不用注册账号。你的对话、记忆和文件都存在这台手机上。",
                "你和 AI 说的话会直接发给你选择的模型服务商（默认 DeepSeek），用的是你自己填写的 API Key，费用由你和服务商结算。",
                "麦克风、相机、日历、联系人、位置这些权限，只在你用到对应功能时才申请，可以拒绝，也可以随时在系统设置里关掉。",
                "AI 的回答可能出错，不能代替医疗、法律、财务等专业意见。",
            ).forEach { line ->
                Row {
                    Text("·  ", color = colors.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Legal.Doc.entries.forEach { d -> TextButton(onClick = { reading = d }) { Text("《${d.title}》") } }
            }
        }
        Text("点「同意」表示你已阅读并同意《用户协议》和《隐私政策》。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) { Text("同意") }
        OutlinedButton(onClick = { sure = true }, modifier = Modifier.fillMaxWidth()) { Text("不同意") }
    }
    reading?.let { d -> LegalDocDialog(d) { reading = null } }
    if (sure) AlertDialog(
        onDismissRequest = { sure = false },
        title = { Text("需要你同意才能使用") },
        text = { Text("不同意《用户协议》和《隐私政策》的话，Kavi 没法为你提供服务。可以再看看协议内容。") },
        confirmButton = { TextButton(onClick = { sure = false }) { Text("再看看") } },
        dismissButton = { TextButton(onClick = onExit) { Text("退出") } },
    )
}

/** 全屏看协议全文。 */
@Composable
fun LegalDocDialog(doc: Legal.Doc, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(doc) { Legal.text(ctx, doc) }
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(doc.title, Modifier.weight(1f).padding(start = 12.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                MarkdownText(text, colors.onBackground, colors.surfaceVariant, textSizeSp = 15f)
            }
        }
    }
}

/** 设置里随时能看：两个按钮。 */
@Composable
fun LegalSection() {
    var reading by rememberSaveable { mutableStateOf<Legal.Doc?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Legal.Doc.entries.forEach { d ->
            OutlinedButton(onClick = { reading = d }, modifier = Modifier.fillMaxWidth()) { Text(d.title) }
        }
    }
    reading?.let { d -> LegalDocDialog(d) { reading = null } }
}
