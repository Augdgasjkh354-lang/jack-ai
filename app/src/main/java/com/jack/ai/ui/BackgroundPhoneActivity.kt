package com.jack.ai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jack.ai.a11y.BackgroundPhone
import com.jack.ai.data.Store
import kotlinx.coroutines.launch

/** 通知和设置页共用的实时后台画面；接管时明确暂停任务。 */
class BackgroundPhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Store(this).loadSettings()
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { BackgroundScreen() }
            }
        }
    }
}

@Composable
private fun BackgroundScreen() {
    val current by BackgroundPhone.current.collectAsState()
    val phone = current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("后台屏幕", style = MaterialTheme.typography.titleLarge)
        if (phone == null) {
            Text("当前没有后台手机任务。")
            return@Column
        }
        val paused by phone.paused.collectAsState()
        val frame by phone.display.frames.collectAsState()
        Text(phone.title)
        Text(if (paused) "已暂停，正在由你接管。处理后回到这里点「继续后台」。" else "Kavi 在这块屏幕里操作，主屏可照常使用其他 App。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && !phone.stopped, onClick = {
                busy = true
                scope.launch {
                    try {
                        val ok = if (paused) phone.resume() else phone.handOver()
                        message = if (ok) "" else "没有切换成功，请稍后再试。"
                    } catch (_: Exception) { message = "助手连接已断开，请重新连接。" }
                    finally { busy = false }
                }
            }) { Text(if (paused) "继续后台" else "接管到主屏") }
            Button(onClick = { phone.stop(); message = "已停止后台手机操作。" }) { Text("停止") }
        }
        if (message.isNotBlank()) Text(message)
        frame?.let { Image(it.asImageBitmap(), "后台 App 实时画面", Modifier.weight(1f), contentScale = ContentScale.Fit) }
    }
}
