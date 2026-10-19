package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

class MailTest {
    private fun parse(raw: String) = MimeMessage(Session.getInstance(Properties()), raw.trimIndent().replace("\n", "\r\n").byteInputStream())

    @Test
    fun htmlBecomesReadableText() {
        val t = MailText.htmlToText("<html><head><style>p{}</style></head><body><p>你好&nbsp;Jack</p><div>第一行<br>第二行</div>" +
            "<ul><li>甲</li><li>乙</li></ul><script>x()</script>&lt;b&gt; &#26126;</body></html>")
        val tidy = MailText.tidy(t)
        assertTrue(tidy.contains("你好 Jack"))
        assertTrue(tidy.contains("第一行\n第二行"))
        assertTrue(tidy.contains("- 甲"))
        assertTrue(tidy.contains("<b> 明"))
        assertFalse(tidy.contains("x()"))
        assertFalse(tidy.contains("p{}"))
    }

    @Test
    fun badNumericEntitiesDoNotBreakTheMail() {
        val t = MailText.htmlToText("a&#99999999;b &#x660E; &#26126;")
        assertEquals("a&#99999999;b 明 明", t)
    }

    @Test
    fun longBodiesAreCut() {
        val t = MailText.tidy("字".repeat(30_000), max = 100)
        assertTrue(t.length < 200)
        assertTrue(t.endsWith("省略了）"))
    }

    @Test
    fun prefersPlainTextInAlternative() {
        val m = parse("""
            From: =?UTF-8?B?5byg5LiJ?= <zhang@163.com>
            Subject: =?UTF-8?B?5ZGo5oql?=
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: 8bit

            这是纯文本
            --b1
            Content-Type: text/html; charset=UTF-8

            <p>这是网页版</p>
            --b1--
        """)
        assertEquals("周报", m.subject)
        val (text, names, _) = MailClient.extract(m, null)
        assertEquals("这是纯文本", text)
        assertTrue(names.isEmpty())
    }

    @Test
    fun attachmentsAreNamedAndSaved() {
        val dir: File = Files.createTempDirectory("mail").toFile()
        val m = parse("""
            From: a@163.com
            Subject: test
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="b2"

            --b2
            Content-Type: text/html; charset=UTF-8

            <div>见附件</div>
            --b2
            Content-Type: application/pdf; name="=?UTF-8?B?5ZCI5ZCMLnBkZg==?="
            Content-Disposition: attachment; filename="=?UTF-8?B?5ZCI5ZCMLnBkZg==?="
            Content-Transfer-Encoding: base64

            SGVsbG8=
            --b2--
        """)
        val (text, names, saved) = MailClient.extract(m, dir)
        assertEquals("见附件", text)
        assertEquals(listOf("合同.pdf"), names)
        assertEquals("Hello", saved.single().readText())
        assertEquals("合同.pdf", saved.single().name)
    }

    @Test
    fun presetsFillServers() {
        val a = mailPresetFor("jack@163.com")!!
        assertEquals("imap.163.com", a.imapHost)
        assertEquals("smtp.163.com", a.smtpHost)
        assertEquals(465, a.smtpPort)
        assertEquals(587, mailPresetFor("x@outlook.com")!!.smtpPort)
        assertNull(mailPresetFor("x@example.org"))
        assertFalse(a.ready)
        assertTrue(a.copy(password = "code").ready)
    }
}
