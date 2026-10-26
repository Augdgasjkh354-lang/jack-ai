package com.jack.ai.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun configPointsAtTheLocalProxy() {
        val c = KaviVpnService.config(43210, "u1", "p2")
        // 顶格的几段，缩进两格的键（转发组件的 YAML 对缩进敏感）
        assertTrue(c, c.startsWith("tunnel:\n  mtu: 8500\n"))
        assertTrue(c.contains("socks5:\n  port: 43210\n  address: 127.0.0.1\n  udp: 'udp'\n  username: 'u1'\n  password: 'p2'\n"))
        assertTrue(c.contains("mapdns:\n  address: ${KaviVpnService.DNS}\n"))
        assertEquals(0, c.lines().count { it.startsWith(" ") && !it.startsWith("  ") })
    }
}
