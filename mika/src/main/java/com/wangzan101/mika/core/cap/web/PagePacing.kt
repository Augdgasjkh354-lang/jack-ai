// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported and adapted from Mika 0091d2d PagePacing.kt.
// Jack AI modification: retain only per-site dwell, using the browser's domain handling.
package com.wangzan101.mika.core.cap.web

import java.net.URI
import java.util.ArrayDeque
import kotlin.random.Random

/** 同网站连着翻页时累计停留，首页及换站不等；调用方负责时钟和延时。 */
class PagePacing(private val random: Random = Random.Default, val limits: Limits = Limits()) {
    data class Limits(
        val firstDwellMs: LongRange = 4_000L..7_000L,
        val perPageMs: Pair<Long, Long> = 600L to 1_000L,
        val maxDwellMs: Long = 12_000L,
        val windowMs: Long = 10 * 60_000L,
    )

    private data class Load(val site: String, val at: Long, var dwell: Long)
    private val loads = ArrayDeque<Load>()

    @Synchronized
    fun noteLoad(url: String, now: Long): Long {
        val site = site(url) ?: return 0
        while (loads.isNotEmpty() && now - loads.first().at > limits.windowMs) loads.removeFirst()
        val seen = loads.count { it.site == site }
        val dwell = if (seen == 0) 0L else {
            val extra = (seen - 1).toLong()
            val min = (limits.firstDwellMs.first + extra * limits.perPageMs.first).coerceAtMost(limits.maxDwellMs)
            val max = (limits.firstDwellMs.last + extra * limits.perPageMs.second).coerceAtMost(limits.maxDwellMs)
            if (min >= max) max else random.nextLong(min, max + 1)
        }
        loads.addLast(Load(site, now, dwell))
        return dwell
    }

    @Synchronized
    fun waitBefore(url: String, now: Long): Long {
        val site = site(url) ?: return 0
        val last = loads.lastOrNull() ?: return 0
        if (last.site != site) return 0
        return (last.dwell - (now - last.at)).coerceIn(0L, limits.maxDwellMs)
    }

    @Synchronized
    fun served(url: String) {
        val site = site(url) ?: return
        loads.lastOrNull()?.takeIf { it.site == site }?.dwell = 0
    }

    companion object {
        /** www、移动站、API 子域视为同网站；常见双段公共后缀保留三级域名。 */
        fun site(url: String): String? {
            val host = runCatching {
                URI(if ("://" in url) url else "https://$url").host?.lowercase()
            }.getOrNull()?.removePrefix("www.") ?: return null
            val parts = host.split('.').filter { it.isNotEmpty() }
            if (parts.size <= 2) return host
            val s = if (parts[parts.size - 2] in setOf("com", "net", "org", "gov", "edu", "co", "ac") &&
                parts.last().length == 2) 3 else 2
            return parts.takeLast(s).joinToString(".")
        }
    }
}
