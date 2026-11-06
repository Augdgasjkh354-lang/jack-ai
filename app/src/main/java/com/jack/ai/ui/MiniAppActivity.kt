package com.jack.ai.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jack.ai.data.Store
import java.io.File

/**
 * 放到桌面的小工具：点桌面上的图标直接打开这个小工具，不用先进聊天找。
 * 思路来自 Momoding 的「小应用放到桌面」（只借思路，没有用它的代码）。
 */
class MiniAppActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = safeFile(this, intent.getStringExtra(EXTRA_PATH))
        val settings = Store(this).loadSettings()
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (file == null) Text("这个小工具找不到了（可能对话被删了或文件改了名）。可以回到 Kavi 里重新放一次。", Modifier.padding(androidx.compose.ui.unit.Dp(24f)))
                    else Scaffold(topBar = { TopAppBar(title = { Text(file.name.removeSuffix(".html")) }) }) { p ->
                        AppWebView(file, Modifier.padding(p).fillMaxSize(), inline = false)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"

        /** 只开 Kavi 自己文件夹里的 html（桌面图标里的路径不能拿来读别处的文件）。 */
        fun safeFile(ctx: Context, path: String?): File? {
            val f = path?.let { File(it).canonicalFile } ?: return null
            val base = ctx.filesDir.canonicalPath + File.separator
            return f.takeIf { it.path.startsWith(base) && it.isFile && it.name.endsWith(".html", true) }
        }

        /** 请桌面放一个图标；桌面不支持时返回 false。 */
        fun pin(ctx: Context, file: File): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
            val intent = Intent(ctx, MiniAppActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val info = ShortcutInfoCompat.Builder(ctx, "app-" + file.absolutePath.hashCode().toUInt().toString(16))
                .setShortLabel(file.name.removeSuffix(".html").take(20))
                .setIcon(IconCompat.createWithResource(ctx, com.jack.ai.R.mipmap.ic_launcher_k))
                .setIntent(intent).build()
            return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
        }
    }
}
