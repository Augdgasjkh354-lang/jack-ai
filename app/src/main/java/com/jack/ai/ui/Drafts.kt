package com.jack.ai.ui

import android.content.Context

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
            if (prefs.contains(key)) prefs.edit().remove(key).apply()
        } else if (prefs.getString(key, null) != text) prefs.edit().putString(key, text).apply()
    }

    private companion object { const val NEW = "_new" }
}
