package com.jack.ai.data

import android.content.Context
import androidx.work.WorkManager

/**
 * 删掉的功能留下的东西：自由时间（以前每天凌晨跑一次）的定时任务要取消，不然 WorkManager 还会去找已经删掉的类。
 * 自由时间的对话 id 还留着：旧的那个对话照旧不进经历流、不参与整理。
 */
object Retired {
    const val FREE_TIME_CONV = "reading"

    fun cleanup(ctx: Context) {
        runCatching {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("reading-daily")
            wm.cancelUniqueWork("reading-now")
        }
    }
}
