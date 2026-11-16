package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class LinuxSandboxTest {
    @Test fun commandLineRunsTheLauncherThenProotWithTheWorkspaceMounted() {
        val argv = LinuxSandbox.argv("/lib/mikarun", "/lib/proot", "/data/rootfs", "/data/ws/c1",
            listOf("/data/proc/stat" to "stat"), timeoutSec = 30, network = false, command = "ls -la", devices = listOf("/dev/null", "/dev/urandom"))
        assertEquals(listOf("/lib/mikarun", "--timeout", "30", "--net", "off", "--", "/lib/proot"), argv.take(7))
        val s = argv.joinToString(" ")
        assertTrue(s.contains("-r /data/rootfs"))
        assertTrue(s.contains("-b /dev/null -b /dev/urandom -b /proc -b /sys"))
        assertTrue(s.contains("-b /data/proc/stat:/proc/stat"))
        assertTrue(s.contains("-b /data/ws/c1:/workspace -w /workspace"))
        assertEquals(listOf("/bin/sh", "-lc", "ls -la"), argv.takeLast(3))
        // 不限时就不传 --timeout；默认联网
        val open = LinuxSandbox.argv("m", "p", "r", "w", emptyList(), 0, true, "true", emptyList())
        assertFalse("--timeout" in open)
        assertEquals("on", open[open.indexOf("--net") + 1])
    }

    @Test fun environmentIsMinimalAndPointsProotAtItsLoader() {
        val env = LinuxSandbox.env("/lib/loader", "/cache/tmp", "Asia/Shanghai")
        assertEquals("/lib/loader", env["PROOT_LOADER"])
        assertEquals("/cache/tmp", env["PROOT_TMP_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("Asia/Shanghai", env["TZ"])
        assertFalse(env.keys.any { it.contains("KEY") || it.contains("TOKEN") })
    }

    @Test fun timezonesBecomeNamesTheSandboxUnderstands() {
        assertEquals("Asia/Shanghai", LinuxSandbox.timezone { ZoneId.of("Asia/Shanghai") })
        assertEquals("Etc/GMT-8", LinuxSandbox.timezone { ZoneOffset.ofHours(8) })
        assertEquals("Etc/GMT+5", LinuxSandbox.timezone { ZoneId.of("GMT-05:00") })
        assertEquals("<+0545>-05:45", LinuxSandbox.timezone { ZoneOffset.ofHoursMinutes(5, 45) })
        assertEquals("UTC", LinuxSandbox.timezone { ZoneOffset.UTC })
    }

    @Test fun outputIsCappedButFullyDrained() {
        val data = ByteArray(10_000) { 'a'.code.toByte() }
        val (kept, dropped) = LinuxSandbox.collect(data.inputStream(), limit = 4_000)
        assertEquals(4_000, kept.size)
        assertEquals(6_000L, dropped)
    }

    @Test fun sha256MatchesAKnownValue() {
        val f = kotlin.io.path.createTempFile().toFile()
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LinuxSandbox.sha256(f))
        } finally { f.delete() }
    }
}
