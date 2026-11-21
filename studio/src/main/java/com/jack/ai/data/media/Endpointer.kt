package com.jack.ai.data.media

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 语音对话里听一句话：判断什么时候开始说、什么时候说完（不联网、不用系统的语音识别）。
 * 20 毫秒一帧算音量：开头 0.3 秒量背景噪声，比它响好几倍连着 3 帧算开始说；说了之后安静 [silenceMs] 就算说完。
 * 一直没开口到 [waitMs] 算超时；一句最长 [maxMs]。
 */
class Endpointer(
    private val rate: Int = AudioPcm.RATE,
    private val silenceMs: Int = 1200,
    private val waitMs: Int = 30_000,
    private val maxMs: Int = 60_000,
) {
    enum class State { WAITING, SPEAKING, DONE, TIMEOUT }

    val frame = rate / 50
    var state = State.WAITING
        private set
    /** 这句话从第几个采样开始、到第几个结束（含开头往前留的 0.3 秒）。 */
    var start = 0
        private set
    var end = 0
        private set
    /** 现在的音量（0～1，界面画声音大小用）。 */
    var level = 0f
        private set

    private var frames = 0
    private var floorSum = 0.0
    private var floor = 0.0
    private var loud = 0
    private var quiet = 0
    private val calib = 15
    private val preroll = 15

    private fun threshold() = max(floor * 3, 400.0)

    /** 再喂一帧（[frame] 个采样），返回现在的状态。 */
    fun push(samples: ShortArray, n: Int = samples.size): State {
        if (state == State.DONE || state == State.TIMEOUT) return state
        var sum = 0.0
        for (i in 0 until n) { val v = samples[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / max(n, 1))
        level = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
        frames++
        val at = frames * frame
        if (frames <= calib) {
            floorSum += rms
            floor = max(floorSum / frames, 80.0)
            return state
        }
        when (state) {
            State.WAITING -> {
                if (rms > threshold()) loud++ else { loud = 0; floor = floor * 0.95 + rms * 0.05 }
                if (loud >= 3) {
                    state = State.SPEAKING
                    start = max(0, at - (loud + preroll) * frame)
                } else if (frames * 20 >= waitMs) state = State.TIMEOUT
            }
            State.SPEAKING -> {
                if (rms < threshold() * 0.6) quiet++ else quiet = 0
                if (quiet * 20 >= silenceMs || at - start >= maxMs * rate / 1000) {
                    state = State.DONE
                    // 句尾留 0.2 秒，后面的静音不要
                    end = at - max(0, quiet - 10) * frame
                }
            }
            else -> {}
        }
        return state
    }
}
