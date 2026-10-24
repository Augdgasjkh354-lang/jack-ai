package com.jack.ai.api

/**
 * Jack 浏览器去广告：按网站名拦掉常见的广告和跟踪请求，再用一小段样式藏起常见的广告位。
 * 名单故意保守：登录、付款、淘宝客跳转这类会被连累的（alimama、mmstat、umeng、googletagmanager）不拦。
 */
object BrowserAds {
    private val DOMAINS = setOf(
        // 国外广告和跟踪
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "googletagservices.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "scorecardresearch.com", "moatads.com", "adsrvr.org", "casalemedia.com",
        "smartadserver.com", "media.net", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "adcolony.com",
        "applovin.com", "inmobi.com", "advertising.com", "zedo.com", "revcontent.com", "mgid.com", "adform.net", "teads.tv",
        "33across.com", "sharethrough.com", "bidswitch.net", "quantserve.com", "yieldmo.com", "ads.yahoo.com",
        // 国内广告和跟踪
        "pos.baidu.com", "cpro.baidu.com", "cbjs.baidu.com", "eclick.baidu.com", "hm.baidu.com", "cpro.baidustatic.com",
        "dup.baidustatic.com", "ubmcmm.baidustatic.com", "tanx.com", "cnzz.com", "gdt.qq.com", "pgdt.gtimg.cn", "adsmind.gdtimg.com",
        "pangolin-sdk-toutiao.com", "pangolin-sdk-toutiao-b.com", "sax.sina.com.cn", "admaster.com.cn", "miaozhen.com",
        "irs01.com", "mediav.com", "adkwai.com", "allyes.com", "ipinyou.com",
    )

    /** 这个网站名是不是广告（名单里的网站和它的子域名）。 */
    fun isAd(host: String): Boolean {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in DOMAINS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** 藏起常见广告位的样式（只挑不容易误伤的写法）。 */
    const val HIDE_CSS = "ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_\"],[id^=\"div-gpt-ad\"],iframe[src*=\"doubleclick.net\"]," +
        "iframe[src*=\"googlesyndication\"],iframe[src*=\"pos.baidu.com\"],[id^=\"cproIframe\"],.adsbox{display:none!important}"

    /** 把上面的样式放进网页（重复执行不会重复加）。 */
    val INJECT_JS = "(function(){if(document.getElementById('jack-adblock'))return;var s=document.createElement('style');" +
        "s.id='jack-adblock';s.textContent=" + kotlinx.serialization.json.JsonPrimitive(HIDE_CSS) + ";" +
        "(document.head||document.documentElement).appendChild(s);})();"
}
