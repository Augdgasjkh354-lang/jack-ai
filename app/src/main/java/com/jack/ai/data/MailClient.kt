package com.jack.ai.data

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

class MailException(message: String) : Exception(message)

/**
 * 收发邮件：IMAP 收信、SMTP 发信。每次调用连一次、用完就断，不常驻后台。
 * 读信用只读方式打开文件夹，不会把邮件标成已读。
 */
object MailClient {
    data class Summary(
        val uid: Long,
        val date: Long,
        val from: String,
        val subject: String,
        val unread: Boolean,
    )

    data class Full(
        val summary: Summary,
        val to: String,
        val cc: String,
        val text: String,
        /** 附件文件名；保存过的附件同时给出保存后的文件。 */
        val attachments: List<String>,
        val saved: List<File>,
    )

    private fun imapSession(acc: MailAccount) = Session.getInstance(Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", acc.imapHost)
        put("mail.imaps.port", acc.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "20000")
        put("mail.imaps.timeout", "30000")
        put("mail.mime.decodetext.strict", "false")
    })

    private fun <T> withStore(acc: MailAccount, block: (IMAPStore) -> T): T = translate {
        val store = imapSession(acc).getStore("imaps") as IMAPStore
        store.connect(acc.imapHost, acc.imapPort, acc.address, acc.password)
        try {
            // 网易邮箱（163/126）要求登录后先报客户端身份，不然会报 Unsafe Login
            runCatching {
                store.id(mapOf("name" to "JackAI", "version" to "1.0", "vendor" to "JackAI", "support-email" to acc.address))
            }
            block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun <T> withFolder(acc: MailAccount, name: String, write: Boolean = false, block: (IMAPFolder) -> T): T =
        withStore(acc) { store ->
            val folder = store.getFolder(name.ifBlank { "INBOX" }) as IMAPFolder
            if (!folder.exists()) {
                throw MailException("没有「$name」这个文件夹。现有的文件夹：" + folderNames(store).joinToString("、"))
            }
            folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            try {
                block(folder)
            } finally {
                runCatching { folder.close(false) }
            }
        }

    private fun folderNames(store: IMAPStore): List<String> =
        store.defaultFolder.list("*").filter { it.type and Folder.HOLDS_MESSAGES != 0 }.map { it.fullName }

    /** 试着登录一下，设置页的「测试」用。 */
    fun test(acc: MailAccount): String = withStore(acc) { store ->
        val inbox = store.getFolder("INBOX")
        "连接成功，收件箱共 ${inbox.messageCount} 封，未读 ${inbox.unreadMessageCount} 封"
    }

    fun folders(acc: MailAccount): List<String> = withStore(acc) { folderNames(it) }

    /**
     * 列出邮件，最新的在前。有关键词或只看未读时，在最近的 300 封里找（网易的服务器搜中文不可靠，所以在手机上筛）。
     */
    fun list(acc: MailAccount, folder: String, limit: Int, unreadOnly: Boolean, query: String, sinceDays: Int?): List<Summary> =
        withFolder(acc, folder) { f ->
            val total = f.messageCount
            if (total == 0) return@withFolder emptyList()
            val window = if (unreadOnly || query.isNotBlank() || sinceDays != null) 300 else limit
            val from = (total - window + 1).coerceAtLeast(1)
            val msgs = f.getMessages(from, total)
            f.fetch(msgs, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            })
            val since = sinceDays?.let { System.currentTimeMillis() - it * 86_400_000L }
            val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            msgs.reversed().asSequence()
                .map { summary(f, it) }
                .filter { !unreadOnly || it.unread }
                .filter { since == null || it.date >= since }
                .filter { s -> words.all { w -> s.subject.contains(w, true) || s.from.contains(w, true) } }
                .take(limit)
                .toList()
        }

    /** 读一封信的全文。saveDir 不为空时把附件存进去（重名自动加编号）。 */
    fun read(acc: MailAccount, folder: String, uid: Long, saveDir: File?): Full = withFolder(acc, folder) { f ->
        val m = f.getMessageByUID(uid) ?: throw MailException("找不到这封邮件（编号 $uid），可能已经被删了")
        val (text, names, saved) = extract(m, saveDir)
        Full(
            summary = summary(f, m),
            to = addresses(m.getRecipients(Message.RecipientType.TO)),
            cc = addresses(m.getRecipients(Message.RecipientType.CC)),
            text = text,
            attachments = names,
            saved = saved,
        )
    }

    fun setRead(acc: MailAccount, folder: String, uid: Long, read: Boolean) = withFolder(acc, folder, write = true) { f ->
        val m = f.getMessageByUID(uid) ?: throw MailException("找不到这封邮件（编号 $uid）")
        m.setFlag(Flags.Flag.SEEN, read)
    }

    /** 发信。replyTo 给出时按回复处理：带上原信的 Message-ID，主题自动加「Re: 」。 */
    fun send(
        acc: MailAccount,
        to: String,
        cc: String,
        subject: String,
        body: String,
        attachments: List<File>,
        replyTo: Pair<String, Long>? = null,
    ): String = translate {
        val session = Session.getInstance(Properties().apply {
            put("mail.smtp.host", acc.smtpHost)
            put("mail.smtp.port", acc.smtpPort.toString())
            put("mail.smtp.auth", "true")
            if (acc.smtpPort == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
        })
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(acc.address))
        val toList = InternetAddress.parse(to.replace('；', ',').replace(';', ','), false)
        if (toList.isEmpty()) throw MailException("没有收件人")
        msg.setRecipients(Message.RecipientType.TO, toList)
        if (cc.isNotBlank()) msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc.replace('；', ',').replace(';', ','), false))
        var subj = subject
        replyTo?.let { (folder, uid) ->
            runCatching {
                withFolder(acc, folder) { f ->
                    val orig = f.getMessageByUID(uid)
                    orig?.getHeader("Message-ID")?.firstOrNull()?.let { id ->
                        msg.setHeader("In-Reply-To", id)
                        msg.setHeader("References", (orig.getHeader("References")?.firstOrNull()?.plus(" ") ?: "") + id)
                    }
                    if (subj.isBlank()) subj = orig?.subject.orEmpty()
                }
            }
            if (!subj.startsWith("Re:", true) && !subj.startsWith("回复")) subj = "Re: $subj"
        }
        msg.setSubject(subj, "UTF-8")
        msg.sentDate = Date()
        if (attachments.isEmpty()) {
            msg.setText(body, "UTF-8")
        } else {
            val mp = MimeMultipart()
            mp.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
            attachments.forEach { file ->
                mp.addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(file))
                    fileName = MimeUtility.encodeText(file.name, "UTF-8", "B")
                })
            }
            msg.setContent(mp)
        }
        Transport.send(msg, acc.address, acc.password)
        "已发送给 ${addresses(toList)}"
    }

    // ---------- 内部 ----------

    private fun summary(f: IMAPFolder, m: Message) = Summary(
        uid = f.getUID(m),
        date = (m.receivedDate ?: m.sentDate)?.time ?: 0L,
        from = addresses(m.from),
        subject = m.subject?.trim().orEmpty().ifEmpty { "（无主题）" },
        unread = !m.isSet(Flags.Flag.SEEN),
    )

    private fun addresses(list: Array<out javax.mail.Address>?): String =
        list.orEmpty().joinToString("、") { a -> (a as? InternetAddress)?.toUnicodeString() ?: a.toString() }

    /** 拆一封信：(整理过的正文, 附件名, 存下来的附件)。 */
    fun extract(m: Part, saveDir: File?): Triple<String, List<String>, List<File>> {
        val text = StringBuilder()
        val names = mutableListOf<String>()
        val saved = mutableListOf<File>()
        walk(m, text, names, saved, saveDir)
        return Triple(MailText.tidy(text.toString()), names, saved)
    }

    /** 把信拆开：正文进 text（有纯文本就不要 HTML），附件记名字，需要时存下来。 */
    private fun walk(p: Part, text: StringBuilder, names: MutableList<String>, saved: MutableList<File>, saveDir: File?) {
        val fileName = p.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        if (fileName != null || Part.ATTACHMENT.equals(p.disposition, true)) {
            val name = Workspace.safeName(fileName ?: "附件")
            names += name
            if (saveDir != null) {
                val f = Workspace.uniqueFile(saveDir, name)
                p.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                saved += f
            }
            return
        }
        when {
            p.isMimeType("text/plain") -> text.append(p.content as? String ?: "").append("\n")
            p.isMimeType("text/html") -> text.append(MailText.htmlToText(p.content as? String ?: "")).append("\n")
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                val plain = parts.firstOrNull { it.isMimeType("text/plain") }
                if (plain != null) walk(plain, text, names, saved, saveDir) else parts.forEach { walk(it, text, names, saved, saveDir) }
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), text, names, saved, saveDir)
            }
            p.isMimeType("message/rfc822") -> (p.content as? Part)?.let { walk(it, text, names, saved, saveDir) }
        }
    }

    /** 把 JavaMail 的英文报错翻成看得懂的话。 */
    private fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: MailException) {
        throw e
    } catch (e: AuthenticationFailedException) {
        throw MailException("登录失败：邮箱地址或授权码不对（要填授权码，不是登录密码）")
    } catch (e: MessagingException) {
        val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
        throw MailException(when {
            "Unsafe Login" in msg -> "网易拦截了这次登录，请到网页版邮箱的设置里确认已开启 IMAP/SMTP 服务"
            "UnknownHost" in msg || e.cause is java.net.UnknownHostException -> "连不上邮箱服务器，检查网络或服务器地址"
            "timed out" in msg || "Timeout" in msg -> "连接邮箱服务器超时"
            else -> "邮箱出错：$msg"
        })
    }
}

/** 邮件正文整理：HTML 转纯文本、去掉多余空行。纯 Kotlin，方便测试。 */
object MailText {
    private val ENTITIES = mapOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")

    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|li|h[1-6]|table)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "- ")
            .replace(Regex("<[^>]+>"), "")
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v, ignoreCase = true) }
        s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        return s
    }

    /** 去掉行尾空白、把三行以上的空行压成一行；太长的截断。 */
    fun tidy(text: String, max: Int = 20_000): String {
        val t = text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (t.length > max) t.take(max) + "\n…（后面太长，省略了）" else t
    }
}
