package com.jack.ai.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class MediaGrabTest {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val files = mutableMapOf<String, ByteArray>()
    private val seen = mutableListOf<Map<String, String>>()
    private val base get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val input = sock.getInputStream().buffered()
                    fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
                    val first = line()
                    val headers = mutableMapOf("_path" to first.split(' ').getOrElse(1) { "/" })
                    while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim() }
                    synchronized(seen) { seen += headers }
                    val body = files[headers["_path"]!!.substringBefore('?')]
                    val out = sock.getOutputStream()
                    if (body == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush()
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private val req get() = MediaGrab.Req("TestUA", "https://www.bilibili.com/video/BV1", { "SESSDATA=x" }, BrowserNet.AUTO)

    @Test
    fun recognisesMediaRequests() {
        assertEquals("hls", MediaGrab.kindOf("https://cdn.x.com/v/index.m3u8?token=1"))
        assertEquals("dash", MediaGrab.kindOf("https://upos-sz.bilivideo.com/upgcxcode/1-1-30080.m4s?e=x"))
        assertEquals("file", MediaGrab.kindOf("https://v.x.com/a/b.mp4"))
        assertEquals("file", MediaGrab.kindOf("https://v26.douyinvod.com/abc/?a=1&mime_type=video_mp4&br=1"))
        assertEquals("file", MediaGrab.kindOf("https://v.x.com/stream/123", range = "bytes=0-"))
        // 图片、脚本、HLS 的分段都不算
        assertNull(MediaGrab.kindOf("https://x.com/a.png", range = "bytes=0-"))
        assertNull(MediaGrab.kindOf("https://x.com/seg-1.ts"))
        assertNull(MediaGrab.kindOf("https://x.com/app.js"))
        assertNull(MediaGrab.kindOf("blob:https://x.com/1"))
    }

    @Test
    fun parsesMasterAndPicksQuality() {
        val m = MediaGrab.parseM3u8("""
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",DEFAULT=NO,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="zh",DEFAULT=YES,URI="audio/zh.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160
            https://other.com/2160.m3u8
        """.trimIndent(), "https://cdn.x.com/v/master.m3u8") as MediaGrab.Playlist.Master
        assertEquals(3, m.variants.size)
        assertEquals("https://cdn.x.com/v/audio/zh.m3u8", m.audios["aud"])
        assertEquals("https://cdn.x.com/v/1080/index.m3u8", MediaGrab.pick(m.variants, 1080)!!.url)
        assertEquals("https://other.com/2160.m3u8", MediaGrab.pick(m.variants)!!.url)
        // 都比上限高：要最低的
        assertEquals(360, MediaGrab.pick(m.variants.drop(1).map { it.copy(height = it.height * 2) } + m.variants[0], 200)!!.height)
    }

    @Test
    fun parsesMediaPlaylists() {
        val p = MediaGrab.parseM3u8("""
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            a.m4s
            #EXT-X-KEY:METHOD=AES-128,URI="/k",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4.5,
            b.m4s
            #EXT-X-ENDLIST
        """.trimIndent(), "https://cdn.x.com/v/1080/index.m3u8") as MediaGrab.Playlist.Media
        assertEquals(listOf("https://cdn.x.com/v/1080/a.m4s", "https://cdn.x.com/v/1080/b.m4s"), p.segments.map { it.url })
        assertEquals(listOf(7L, 8L), p.segments.map { it.seq })
        assertNull(p.segments[0].keyUrl)
        assertEquals("https://cdn.x.com/k", p.segments[1].keyUrl)
        assertEquals(15, p.segments[1].iv!![15].toInt())
        assertEquals("https://cdn.x.com/v/1080/init.mp4", p.init)
        assertTrue(p.ended); assertNull(p.unsupported); assertEquals(8.5, p.seconds, 0.01)
        val drm = MediaGrab.parseM3u8("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://1\"\n#EXTINF:4,\na.ts\n", "https://x.com/") as MediaGrab.Playlist.Media
        assertTrue(drm.unsupported.orEmpty().contains("SAMPLE-AES"))
        assertFalse(drm.ended)
    }

    @Test
    fun picksBilibiliStreams() {
        val pi = """{"code":0,"data":{"dash":{"video":[
            {"id":120,"height":2160,"codecid":7,"bandwidth":9000,"baseUrl":"https://up/4k.m4s"},
            {"id":80,"height":1080,"codecid":12,"bandwidth":3000,"baseUrl":"https://up/1080-hevc.m4s"},
            {"id":80,"height":1080,"codecid":7,"bandwidth":3500,"base_url":"https://up/1080-avc.m4s"},
            {"id":64,"height":720,"codecid":7,"bandwidth":2000,"baseUrl":"https://up/720.m4s"}],
            "audio":[{"id":30216,"bandwidth":60000,"codecs":"mp4a.40.2","baseUrl":"https://up/a64.m4s"},
                     {"id":30280,"bandwidth":190000,"codecs":"mp4a.40.2","baseUrl":"https://up/a192.m4s"}]}}}"""
        val p = MediaGrab.bilibili(pi, 1080)!!
        assertEquals("https://up/1080-avc.m4s", p.video); assertEquals("https://up/a192.m4s", p.audio); assertEquals("1080p", p.quality)
        assertEquals("https://up/4k.m4s", MediaGrab.bilibili(pi)!!.video)
        assertEquals("https://up/v.mp4", MediaGrab.bilibili("""{"data":{"durl":[{"url":"https://up/v.mp4"}]}}""")!!.direct)
        assertNull(MediaGrab.bilibili("not json"))
    }

    @Test
    fun tellsVideoFromAudioByHeader() {
        fun box(handler: String) = ByteArray(40) + "....hdlr".toByteArray() + ByteArray(8) + handler.toByteArray() + ByteArray(20)
        assertEquals("video", MediaGrab.trackType(box("vide")))
        assertEquals("audio", MediaGrab.trackType(box("soun")))
        assertNull(MediaGrab.trackType(ByteArray(100)))
    }

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

    @Test
    fun downloadsAndDecryptsHls() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val seg0 = ByteArray(5000) { 1 }
        val seg1 = ByteArray(3000) { 2 }
        val seg2 = ByteArray(1000) { 3 }
        files["/master.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720\nhigh/index.m3u8\n".toByteArray()
        files["/high/index.m3u8"] = ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:2,\ns0.ts\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/key.bin\"\n#EXTINF:2,\ns1.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\n$base/high/s2.ts\n#EXT-X-ENDLIST\n").toByteArray()
        files["/key.bin"] = key
        files["/high/s0.ts"] = seg0
        // 没写 IV：用分段序号（第二段是 6）
        files["/high/s1.ts"] = encrypt(seg1, key, MediaGrab.ivOf(6))
        files["/high/s2.ts"] = seg2
        val dir = File(System.getProperty("java.io.tmpdir"), "hls-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val v = File(dir, "v.ts")
            var last = 0
            val (separate, secs) = MediaGrab.hls(req, "$base/master.m3u8", v, File(dir, "a.ts"), 10_000_000, 1080) { done, _, _ -> last = done }
            assertFalse(separate); assertEquals(6.0, secs, 0.01); assertEquals(3, last)
            assertTrue(v.readBytes().contentEquals(seg0 + seg1 + seg2))
            // 带着 Cookie 和 Referer（B 站这类没它们会 403）
            val h = seen.first { it["_path"] == "/high/s0.ts" }
            assertEquals("SESSDATA=x", h["cookie"]); assertEquals("https://www.bilibili.com/video/BV1", h["referer"])
            // 直播（没有结尾）不下
            files["/live.m3u8"] = "#EXTM3U\n#EXTINF:2,\ns0.ts\n".toByteArray()
            val e = runCatching { MediaGrab.hls(req, "$base/live.m3u8", v, File(dir, "a.ts"), 10_000_000) }.exceptionOrNull()
            assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("直播"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
