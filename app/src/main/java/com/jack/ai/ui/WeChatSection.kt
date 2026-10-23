package com.jack.ai.ui

import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jack.ai.ChatViewModel
import com.jack.ai.WeChatListener
import com.jack.ai.data.Settings

/** 设置页的「微信消息」：微信私聊交给 Kavi，客户的事记进客户表，要紧的推到手机。 */
@Composable
fun WeChatSection(vm: ChatViewModel, s: Settings) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 从系统设置回来时重新看一眼有没有给权限
    var granted by remember { mutableStateOf(WeChatListener.granted(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = WeChatListener.granted(ctx)
        if (granted && s.wechatWatch) WeChatListener.rebind(ctx)
        onPauseOrDispose {}
    }
    var skip by remember { mutableStateOf(s.wechatSkip) }

    Text(
        "微信来私聊时，Kavi 过几分钟整批看一遍：认得出是哪位客户就记进「客户」「跟进记录」表；新客户或拿不准的，" +
            "在「微信客户」对话里列出来等你回一句确认；客户问价、约时间、等你回复这类要紧的，用连接器（比如 Bark）推到你的 iPhone。" +
            "家人朋友的闲聊不记也不推。\n" +
            "要求：这台手机登着微信、微信设置里打开「通知显示消息详情」；群聊不看。" +
            "消息原文会发给 V4.1 Flash 分析，每批算一次花费。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("微信消息交给 Kavi")
            Text(
                when {
                    !granted -> "还没给「通知使用权」，读不到微信通知"
                    s.wechatWatch -> "开着"
                    else -> "关着"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!granted && s.wechatWatch) colors.error else colors.onSurfaceVariant,
            )
        }
        Switch(checked = s.wechatWatch, onCheckedChange = { on ->
            vm.updateSettings { it.copy(wechatWatch = on) }
            if (on && granted) WeChatListener.rebind(ctx)
        })
    }
    if (!granted) OutlinedButton(onClick = {
        runCatching { ctx.startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) { Text("去给通知使用权") }
    OutlinedTextField(
        value = skip,
        onValueChange = {
            skip = it
            vm.updateSettings { st -> st.copy(wechatSkip = it) }
        },
        label = { Text("不看这些人（一行一个，写微信里显示的名字）") },
        placeholder = { Text("妈妈\n老婆") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}
