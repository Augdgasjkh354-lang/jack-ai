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
import com.jack.ai.a11y.PipInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        val operating by phone.operating.collectAsState()
        val frame by phone.display.frames.collectAsState()
        Text(phone.title)
        Text(when {
            operating -> "Kavi 已暂停，你直接在下面的画面上点、滑，弄好了点「继续后台」交还给 Kavi。"
            paused -> "已暂停，正在由你接管。处理后回到这里点「继续后台」。"
            else -> "Kavi 在这块屏幕里操作，主屏可照常使用其他 App。"
        })
        fun run(action: suspend () -> Boolean, fail: String) {
            busy = true
            scope.launch {
                try { message = if (action()) "" else fail }
                catch (_: Exception) { message = "助手连接已断开，请重新连接。" }
                finally { busy = false }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) {
                Button(enabled = !busy && !phone.stopped, onClick = { run({ phone.resume() }, "没有切换成功，请稍后再试。") }) { Text("继续后台") }
                if (operating) androidx.compose.material3.OutlinedButton(enabled = !phone.stopped,
                    onClick = { scope.launch { if (!phone.userBack()) message = "返回键没按成功。" } }) { Text("返回") }
            } else {
                Button(enabled = !busy && !phone.stopped, onClick = { run({ phone.operateHere() }, "没有暂停成功，请稍后再试。") }) { Text("在这里操作") }
                androidx.compose.material3.OutlinedButton(enabled = !busy && !phone.stopped, onClick = { run({ phone.handOver() }, "没有切换成功，请稍后再试。") }) { Text("接管到主屏") }
            }
            androidx.compose.material3.OutlinedButton(onClick = { phone.stop(); message = "已停止后台手机操作。" }) { Text("停止") }
        }
        if (message.isNotBlank()) Text(message)
        frame?.let { bmp ->
            val (screenW, screenH) = phone.display.width to phone.display.height
            var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val touch = if (!operating) Modifier else Modifier.pointerInput(bmp.width, bmp.height, screenW, screenH) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.uptimeMillis
                    val xs = mutableListOf(down.position.x); val ys = mutableListOf(down.position.y); val ts = mutableListOf(0L)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        xs += c.position.x; ys += c.position.y; ts += c.uptimeMillis - start
                        c.consume()
                        if (!c.pressed) break
                    }
                    val g = PipInput.Gesture(xs, ys, ts)
                    val w = box.width.toFloat(); val h = box.height.toFloat()
                    val map = { x: Float, y: Float -> PipInput.map(x, y, w, h, bmp.width, bmp.height, screenW, screenH) }
                    scope.launch {
                        val ok = if (PipInput.isTap(g)) map(xs.first(), ys.first())?.let { phone.userTap(it.x, it.y) } ?: true
                        else PipInput.stroke(g, map)?.let { (sx, sy, st) -> phone.userStroke(sx, sy, st) } ?: true
                        if (!ok) message = "这一下没有发到后台屏幕。"
                    }
                }
            }
            Image(bmp.asImageBitmap(), "后台 App 实时画面", Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { box = it }.then(touch), contentScale = ContentScale.Fit)
        }
    }
}
