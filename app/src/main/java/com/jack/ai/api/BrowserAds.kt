package com.jack.ai.api

/**
 * Kavi 浏览器去广告：按网站名拦掉常见的广告和跟踪请求，再用一小段样式藏起常见的广告位。
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

    /**
     * 跟踪器：只记录你在网页上点了什么、看了多久、录屏回放的统计服务，拦掉网页照常能用。
     * 同样保守：登录、支付、防刷要用的（facebook.net、fpjs、umeng、阿里的 mmstat）不拦。
     */
    private val TRACKERS = setOf(
        // 国外：行为分析、录屏回放、广告转化像素
        "hotjar.com", "hotjar.io", "mouseflow.com", "fullstory.com", "clarity.ms", "crazyegg.com", "luckyorange.com", "luckyorange.net",
        "inspectlet.com", "smartlook.com", "logrocket.io", "mixpanel.com", "amplitude.com", "heap.io", "heapanalytics.com",
        "segment.io", "segment.com", "kissmetrics.com", "chartbeat.com", "chartbeat.net", "parsely.com", "comscore.com",
        "bat.bing.com", "analytics.tiktok.com", "ct.pinterest.com", "px.ads.linkedin.com", "snap.licdn.com", "sc-static.net",
        "analytics.twitter.com", "static.ads-twitter.com", "nr-data.net", "quantcount.com", "omtrdc.net", "demdex.net",
        "everesttech.net", "bluekai.com", "krxd.net", "exelator.com", "agkn.com", "rlcdn.com", "tapad.com", "adsymptotic.com",
        // 国内：统计和行为分析
        "growingio.com", "zhugeio.com", "51.la", "talkingdata.com", "tongji.baidu.com", "sensorsdata.cn", "gridsumdissector.com",
        "cnzz.net", "umtrack.com", "ta.qq.com", "pingjs.qq.com", "tajs.qq.com", "log.mmstat.cn", "hm.baidu.com",
    )

    /** 这个网站名是不是广告（名单里的网站和它的子域名）。 */
    fun isAd(host: String): Boolean = inList(host, DOMAINS)

    /** 这个网站名是不是跟踪器（广告名单之外的）。 */
    fun isTracker(host: String): Boolean = inList(host, TRACKERS)

    private fun inList(host: String, list: Set<String>): Boolean {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in list) return true
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
