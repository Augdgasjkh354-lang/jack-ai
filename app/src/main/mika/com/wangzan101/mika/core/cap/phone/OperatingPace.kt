/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: Adapted to Kavi's phone tools without Mika's Agent/UI dependencies.
 */
package com.wangzan101.mika.core.cap.phone

import kotlin.random.Random

/** 设置里的操作节奏。Jack AI modification: 仅保留本阶段需要的「像人 / 快」两档。 */
enum class OperatingPace {
    HUMAN, FAST;

    fun gesture(random: Random = Random.Default): GesturePacing =
        if (this == FAST) GesturePacing.Instant else GesturePacing.human(random)

    fun typing(random: Random = Random.Default): TypingPacing =
        if (this == FAST) TypingPacing.Instant else TypingPacing.human(random)

    companion object {
        val DEFAULT = HUMAN
        fun parse(value: String?): OperatingPace =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
