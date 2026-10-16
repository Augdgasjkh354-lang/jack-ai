package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.MailAccount
import com.jack.ai.data.mailPresetFor
import kotlinx.coroutines.launch

/**
 * 设置页里的邮箱：可以有好几个。填好保存后收成一行（地址 + 修改/删除），第一个是默认邮箱。
 * 163/126/QQ/Gmail/Outlook 自动填服务器，其他的展开手动填。
 */
@Composable
fun MailSection(vm: ChatViewModel, mails: List<MailAccount>) {
    // 正在编辑第几个；-1 是新加的，null 是都收着
    var editing by remember { mutableStateOf<Int?>(if (mails.isEmpty()) -1 else null) }
    fun saveAll(list: List<MailAccount>) = vm.updateSettings { it.copy(mails = list) }
    mails.forEachIndexed { i, m ->
        if (editing == i) {
            MailEditor(vm, m, onSave = { a -> saveAll(mails.toMutableList().also { it[i] = a }); editing = null },
                onCancel = { editing = null })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    m.address + if (i == 0 && mails.size > 1) "（默认）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (i > 0) TextButton(onClick = { saveAll(listOf(m) + mails.filterIndexed { j, _ -> j != i }) }) { Text("设为默认") }
                TextButton(onClick = { editing = i }) { Text("修改") }
                TextButton(onClick = { saveAll(mails.filterIndexed { j, _ -> j != i }); if (editing != null && editing!! > i) editing = editing!! - 1 }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (editing == -1) {
        MailEditor(vm, MailAccount(), onSave = { a -> saveAll(mails.filterNot { it.address == a.address } + a); editing = null },
            onCancel = if (mails.isEmpty()) null else ({ editing = null }))
    } else if (editing == null) {
        TextButton(onClick = { editing = -1 }) { Text("添加邮箱") }
    }
}

@Composable
private fun MailEditor(vm: ChatViewModel, initial: MailAccount, onSave: (MailAccount) -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf(initial) }
    var showServers by remember { mutableStateOf(initial.address.isNotBlank() && mailPresetFor(initial.address) == null) }
    var result by remember { mutableStateOf<String?>(null) }
    fun change(a: MailAccount) { acc = a; result = null }
    OutlinedTextField(
        value = acc.address,
        onValueChange = { v ->
            val preset = mailPresetFor(v)
            change(preset?.copy(password = acc.password) ?: acc.copy(address = v.trim()))
            if (preset == null && v.contains('@') && v.substringAfter('@').contains('.')) showServers = true
        },
        label = { Text("邮箱地址") },
        placeholder = { Text("name@163.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = acc.password,
        onValueChange = { change(acc.copy(password = it.trim())) },
        label = { Text("授权码") },
        placeholder = { Text("不是登录密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (showServers) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(acc.imapHost, { change(acc.copy(imapHost = it.trim())) }, label = { Text("IMAP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(acc.imapPort.toString(), { change(acc.copy(imapPort = it.toIntOrNull() ?: acc.imapPort)) },
                label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(acc.smtpHost, { change(acc.copy(smtpHost = it.trim())) }, label = { Text("SMTP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(acc.smtpPort.toString(), { change(acc.copy(smtpPort = it.toIntOrNull() ?: acc.smtpPort)) },
                label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = acc.ready && result != "…", onClick = { onSave(acc) }) { Text("保存") }
        TextButton(enabled = acc.ready && result != "…", onClick = {
            result = "…"
            scope.launch { result = vm.testMail(acc) }
        }) { Text("测试") }
        if (!showServers) TextButton(onClick = { showServers = true }) { Text("服务器") }
        onCancel?.let { TextButton(onClick = it) { Text("取消") } }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
