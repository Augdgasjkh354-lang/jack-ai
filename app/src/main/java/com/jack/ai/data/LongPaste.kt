package com.jack.ai.data

import android.content.Context
import java.io.File

/**
 * 粘贴长文变文件：一下子贴进输入框很长一段（文章、聊天记录、代码），不放在输入框里卡着，存成一个 .txt 附件发。
 * 思路参考 AAswordman/Operit（只借思路，代码自己写）。
 */
object LongPaste {
    /** 一次贴进来超过这么多字就存成文件。 */
    const val THRESHOLD = 3000

    /** 输入框从 [old] 变成 [new]：是一次贴进来一大段的话，返回贴进来的那段，否则 null（打字、删字、语音塞进来的都不算）。 */
    fun inserted(old: String, new: String): String? {
        if (new.length - old.length < THRESHOLD) return null
        var head = 0
        val max = old.length
        while (head < max && old[head] == new[head]) head++
        var tail = 0
        while (tail < max - head && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
        // 只是插进来，原来的字都还在
        if (head + tail != old.length) return null
        return new.substring(head, new.length - tail).takeIf { it.isNotBlank() }
    }

    /** 存成待发的附件：文件在 staging 里（发出去时挪进对话文件夹），文字直接带上，Kavi 不用再读。 */
    fun attachment(ctx: Context, text: String): Attachment {
        val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(12)
        val name = Workspace.safeName("粘贴-${first.ifBlank { "文字" }}.txt")
        val att = Attachment(kind = "file", name = name)
        val f = File(File(ctx.filesDir, "staging/${att.id}").apply { mkdirs() }, name)
        f.writeText(text)
        return att.copy(path = f.absolutePath, text = if (text.length > 200_000) text.take(200_000) + "\n…（太长，后面省略）" else text)
    }
}
