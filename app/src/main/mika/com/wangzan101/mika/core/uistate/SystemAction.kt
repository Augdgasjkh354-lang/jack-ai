// Jack AI modification: omit unused agent action-result wrapper.
// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported from Mika; Jack AI adaptations documented in third-party/mika/README.md.
package com.wangzan101.mika.core.uistate

/**
 * 经安卓标准接口(intent)做的系统动作:设闹钟、计时器,打开闹钟列表,打开预填好的新日程。
 * 由手机上装着的时钟、日历 App 接手;没有 App 接时端口报 [SystemActionResult.NOT_SUPPORTED],模型退回去操作界面。
 */
sealed interface SystemAction {
    /** [days] 每周重复的日子,按 java.util.Calendar 的写法(1 = 周日 … 7 = 周六),空 = 只响一次。 */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null, val days: List<Int> = emptyList(), val vibrate: Boolean? = null) : SystemAction
    data class SetTimer(val seconds: Int, val label: String? = null) : SystemAction
    data object ShowAlarms : SystemAction
    /** 起止为毫秒时间戳;[allDay] 时只看日期。 */
    data class AddCalendarEvent(
        val title: String, val beginMs: Long, val endMs: Long? = null, val allDay: Boolean = false,
        val location: String? = null, val description: String? = null,
    ) : SystemAction

    /** 交给 App 后不留界面(闹钟、计时器);其余在 Mika 的屏上打开那个 App 的页面。 */
    val silent: Boolean get() = this is SetAlarm || this is SetTimer
}
