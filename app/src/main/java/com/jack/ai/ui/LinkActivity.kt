package com.jack.ai.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jack.ai.api.BrowserPages

/**
 * Kavi 当默认浏览器时，别的 App 里点的网页链接先到这里，转给 Kavi 浏览器（它本身不对外开放）。
 * 只收 http/https 网址，别的一概不接；不带任何别的参数过去。
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { BrowserPages.isWeb(it) }
        if (url != null) runCatching {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
