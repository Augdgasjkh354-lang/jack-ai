package com.jack.ai.api

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Kavi 直连时查域名用加密 DNS（阿里 DoH，走 HTTPS）：连的 Wi-Fi、运营商看不到你访问了哪些网站。
 * 走代理的请求由节点那边解析，不经过这里。查不到（DoH 被拦、局域网里的名字）就退回手机自带的 DNS，网络照样能用。
 */
object SecureDns : Dns {
    /** 查过的记 5 分钟，免得每个请求都多一来一回。 */
    private const val TTL = 5 * 60_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val doh: DnsOverHttps by lazy {
        // 查 DNS 的这个连接自己不走代理、也不用自己（直接连阿里的 IP）
        val bootstrap = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("223.6.6.6"))
            .includeIPv6(true)
            .build()
    }

    /** 不用查的：IP 本身、本机、局域网里的名字（.local、.lan、没有点的）。 */
    fun skip(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "localhost" || '.' !in h || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ':' in h
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 加强保护关着：和原来一样用手机自带的 DNS
        if (skip(hostname) || !Net.config.hardened) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (at, list) -> if (now - at < TTL) return list }
        val list = runCatching { doh.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return Dns.SYSTEM.lookup(hostname)
        cache[hostname] = now to list
        return list
    }
}
