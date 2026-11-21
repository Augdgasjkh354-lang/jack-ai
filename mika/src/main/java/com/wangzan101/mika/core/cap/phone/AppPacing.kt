/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: adapted to Kavi's small phone-state inputs, without Mika Agent/ScreenPort.
 */
package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.Pace
import kotlin.random.Random

/** Mika 的 App 节奏：从前一动作完成时计时，模型思考的时间计入停留。 */
class AppPacing(
    private val random: Random?,
    val limits: Limits = Limits(),
    val careful: Limits = Limits.CAREFUL,
    val screenCap: Int = 24,
) {
    data class Limits(
        val sameScreenMs: LongRange = 500L..1_200L,
        val newScreenMs: LongRange = 900L..2_000L,
        val readMsPerChar: Long = 0L,
        val maxReadMs: Long = 0L,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 600L..1_500L,
        val maxDwellMs: Long = 3_500L,
    ) {
        companion object {
            val CAREFUL = Limits(
                sameScreenMs = 900L..2_200L, newScreenMs = 2_500L..5_000L,
                readMsPerChar = 15, maxReadMs = 8_000,
                pauseChance = 0.1, pauseMs = 2_000L..5_000L, maxDwellMs = 15_000,
            )
        }
    }

    private data class Last(val app: String?, val at: Long, val newScreen: Boolean, val base: Long, val limits: Limits)
    private var last: Last? = null
    private var lastBatch: Pair<String?, String>? = null
    private var consecutive = 0
    private val screens = mutableMapOf<String, Int>()
    private val outward = mutableSetOf<String>()

    @Synchronized
    fun noteAction(
        app: String?, atMs: Long, newScreen: Boolean, careful: Boolean = false,
        counts: Boolean = newScreen, batchKind: String? = null,
    ) {
        val r = random ?: return
        val l = if (careful) this.careful else limits
        var base = Pace.pick(r, if (newScreen) l.newScreenMs else l.sameScreenMs)
        if (r.nextDouble() < l.pauseChance) base += Pace.pick(r, l.pauseMs)
        val key = batchKind?.let { app to it }
        consecutive = if (key != null && key == lastBatch) consecutive + 1 else 1
        lastBatch = key
        // 连续对不同对象重复同类动作时，在原本停留上加随机间隔。
        if (key != null && consecutive >= 2) {
            base += Pace.pick(r, if (careful) 1_500L..3_500L else 700L..1_800L)
        }
        last = Last(app, atMs, newScreen, base.coerceAtMost(l.maxDwellMs), l)
        if (newScreen && counts && careful && app != null) screens[app] = (screens[app] ?: 0) + 1
    }

    @Synchronized
    fun waitBefore(app: String?, nowMs: Long, textChars: Int = 0): Long {
        val value = last?.takeIf { it.app == app } ?: return 0
        val read = if (value.newScreen)
            (textChars.coerceAtLeast(0).toLong() * value.limits.readMsPerChar).coerceAtMost(value.limits.maxReadMs)
        else 0L
        val dwell = (value.base + read).coerceAtMost(value.limits.maxDwellMs)
        return (dwell - (nowMs - value.at)).coerceIn(0L, value.limits.maxDwellMs)
    }

    @Synchronized
    fun capReached(app: String?, careful: Boolean): Boolean =
        random != null && app != null && careful && app !in outward && (screens[app] ?: 0) >= screenCap

    @Synchronized
    fun screensIn(app: String): Int = screens[app] ?: 0

    @Synchronized
    fun outwardDone(app: String) { if (random != null) outward += app }

    @Synchronized
    fun userAnswered() { screens.clear() }

    companion object {
        val Off = AppPacing(null)
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            AppPacing(random, limits, careful)
    }
}
