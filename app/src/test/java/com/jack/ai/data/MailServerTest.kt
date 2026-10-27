package com.jack.ai.data

import com.icegreen.greenmail.util.DummySSLSocketFactory
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.Security
import javax.mail.Folder

/** 在内存邮件服务器上跑删信、移信、清空已删除。 */
class MailServerTest {
    private lateinit var server: GreenMail
    private lateinit var acc: MailAccount

    // Robolectric 的测试会把 Conscrypt 装成整个 JVM 默认的加密实现，GreenMail 的 SSL 服务器在它上面起不来
    // （报不支持的加密套件）。这个测试跑的时候先拿掉，跑完放回原位
    private var conscrypt: java.security.Provider? = null
    private var conscryptAt = 0

    @Before
    fun start() {
        Security.getProviders().withIndex().firstOrNull { it.value.name == "Conscrypt" }?.let { (i, p) ->
            conscrypt = p; conscryptAt = i + 1
            Security.removeProvider(p.name)
        }
        Security.setProperty("ssl.SocketFactory.provider", DummySSLSocketFactory::class.java.name)
        // 随机空闲端口；整套测试一起跑时 SSL 起得慢，默认 2 秒等不到，放宽到 30 秒
        server = GreenMail(arrayOf(ServerSetupTest.IMAPS.dynamicPort().apply { setServerStartupTimeout(30_000) }))
        server.start()
        val user = server.setUser("jack@test.com", "jack@test.com", "pw")
        val port = server.imaps.port
        acc = MailAccount("jack@test.com", "pw", "127.0.0.1", port, "127.0.0.1", 465)
        // 建好「已删除」和「归档」
        val store = server.imaps.createStore()
        store.connect("127.0.0.1", port, "jack@test.com", "pw")
        listOf("已删除", "归档").forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES) }
        store.close()
        repeat(5) { i -> user.deliver(GreenMailUtil.createTextEmail("jack@test.com", "a@b.com", "广告 $i", "正文", server.imaps.serverSetup)) }
    }

    @After
    fun stop() {
        server.stop()
        conscrypt?.let { Security.insertProviderAt(it, conscryptAt) }
    }

    private fun inbox() = MailClient.list(acc, "INBOX", 30, false, "", null)

    @Test
    fun deleteMovesToTrashThenPermanently() {
        val uids = inbox().map { it.uid }
        val (found, trash) = MailClient.inspect(acc, "INBOX", uids.take(2) + 99999L)
        assertEquals(2, found.size)
        assertEquals("已删除", trash)

        assertEquals(2 to "已删除", MailClient.delete(acc, "INBOX", uids.take(2), permanent = false))
        assertEquals(3, inbox().size)
        val inTrash = MailClient.list(acc, "已删除", 30, false, "", null)
        assertEquals(2, inTrash.size)

        // 在「已删除」里再删就是彻底删除
        assertEquals(1 to null, MailClient.delete(acc, "已删除", listOf(inTrash.first().uid), permanent = false))
        assertEquals(1, MailClient.trashInfo(acc).second)

        // 收件箱里直接彻底删除
        assertEquals(1 to null, MailClient.delete(acc, "INBOX", listOf(uids[2]), permanent = true))
        assertEquals(2, inbox().size)
        assertEquals(1, MailClient.trashInfo(acc).second)
    }

    @Test
    fun moveAndEmptyTrash() {
        val uids = inbox().map { it.uid }
        assertEquals(3, MailClient.move(acc, "INBOX", uids.take(3), "归档"))
        assertEquals(2, inbox().size)
        assertEquals(3, MailClient.list(acc, "归档", 30, false, "", null).size)

        val err = runCatching { MailClient.move(acc, "INBOX", uids.drop(3), "不存在") }.exceptionOrNull()
        assertTrue(err is MailException && err.message!!.contains("现有的文件夹"))

        MailClient.delete(acc, "INBOX", uids.drop(3), permanent = false)
        assertEquals(2, MailClient.emptyTrash(acc))
        assertEquals(0, MailClient.trashInfo(acc).second)
        assertEquals(0, inbox().size)
    }

    @Test
    fun bodySearchFindsWordsInTheText() {
        val user = server.setUser("jack@test.com", "jack@test.com", "pw")
        user.deliver(GreenMailUtil.createTextEmail("jack@test.com", "boss@b.com", "周会", "请把合同改好发我", server.imaps.serverSetup))
        // 只搜主题和发件人找不到，连正文搜才找到
        assertEquals(0, MailClient.list(acc, "INBOX", 30, false, "合同", null).size)
        val hits = MailClient.list(acc, "INBOX", 30, false, "合同", null, searchBody = true)
        assertEquals(listOf("周会"), hits.map { it.subject })
    }
}
