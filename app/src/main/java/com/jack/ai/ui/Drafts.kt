package com.jack.ai.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 每个对话没发出去的草稿，存在手机上：App 被系统清掉、重开之后，写了一半的话还在。
 * 还没建出来的新对话用 [NEW] 这个键。
 */
internal class DraftStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun get(convId: String?): String = prefs.getString(convId ?: NEW, null).orEmpty()

    fun put(convId: String?, text: String) {
        val key = convId ?: NEW
        if (text.isBlank()) {
            if (prefs.contains(key)) { prefs.edit().remove(key).apply(); changes++ }
        } else if (prefs.getString(key, null) != text) { prefs.edit().putString(key, text).apply(); changes++ }
    }

    companion object {
        private const val NEW = "_new"
        /** 草稿每改一次加一：侧边栏读它，草稿变了就重新看哪些对话有草稿。 */
        var changes by androidx.compose.runtime.mutableIntStateOf(0)
            private set
    }
}
