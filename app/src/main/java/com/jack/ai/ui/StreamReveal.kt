package com.jack.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * 让回答柔和地流出来：模型一次吐一大段，屏幕上按打字的节奏一点点放出来。
 * 输出中固定每秒 100 字（每帧一两个字，连续地流，看得清在写什么）；模型写得更快的部分先攒着，
 * 模型写完后剩下的在一两秒内加速放完，不让人干等。思考过程不经过这里，照模型原速。
 */
object StreamReveal {
    /** 每条消息已经放出来多少字。消息滑出屏幕再滑回来时接着放，不会从头重放。 */
    internal val shown = HashMap<String, Int>()

    /** 还有字没放完（回答已经收完了也可能还在放）：聊天列表据此继续跟着往下走。 */
    var pending by mutableStateOf(false)
        internal set

    /** 这一帧放多少字。backlog 是还没放出来的字数，dt 是距上一帧的秒数，live 是模型是否还在输出。 */
    internal const val LIVE_RATE = 100.0

    /** 写完后：每秒放剩下的两倍，最少每秒 150 字。剩 600 字约一秒半放完，剩 3000 字约两秒半。 */
    internal fun charsPerSecond(backlog: Int, live: Boolean): Double =
        if (live) LIVE_RATE else maxOf(150.0, backlog * 2.0)
}

/** 返回现在该显示的那一段文字。streaming=false 且以前没放过的消息（翻历史）直接整段显示。 */
@Composable
fun rememberRevealedText(id: String, full: String, streaming: Boolean): String {
    var len by remember(id) { mutableIntStateOf(StreamReveal.shown[id] ?: if (streaming) 0 else full.length) }
    val latest by rememberUpdatedState(full)
    val live by rememberUpdatedState(streaming)
    LaunchedEffect(id, streaming) {
        var acc = 0.0
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 1.0 / 60 else ((now - last) / 1e9).coerceAtMost(0.1)
            last = now
            val target = latest.length
            if (len > target) len = target // 内容被换掉了（比如出错重来）
            val backlog = target - len
            if (backlog <= 0) {
                StreamReveal.shown[id] = len
                if (!live) { StreamReveal.pending = false; break }
                acc = 0.0
                continue
            }
            StreamReveal.pending = true
            acc += StreamReveal.charsPerSecond(backlog, live) * dt
            val step = acc.toInt()
            if (step > 0) {
                acc -= step
                var next = (len + step).coerceAtMost(target)
                // 别把一个表情（两个字符）切成两半
                if (next < target && Character.isHighSurrogate(latest[next - 1])) next++
                len = next
                StreamReveal.shown[id] = len
            }
        }
    }
    return if (len >= full.length) full else full.substring(0, len)
}
