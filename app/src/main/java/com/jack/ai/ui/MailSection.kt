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

/** 设置页里的邮箱：地址、授权码；163/126/QQ/Gmail/Outlook 自动填服务器，其他的展开手动填。 */
@Composable
fun MailSection(vm: ChatViewModel, mail: MailAccount?) {
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf(mail ?: MailAccount()) }
    var showServers by remember { mutableStateOf(mail != null && mailPresetFor(mail.address) == null) }
    var result by remember { mutableStateOf<String?>(null) }
    fun save(a: MailAccount) {
        acc = a
        result = null
        vm.updateSettings { it.copy(mail = a.takeIf { m -> m.address.isNotBlank() || m.password.isNotBlank() }) }
    }
    OutlinedTextField(
        value = acc.address,
        onValueChange = { v ->
            val preset = mailPresetFor(v)
            save(preset?.copy(password = acc.password) ?: acc.copy(address = v.trim()))
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
        onValueChange = { save(acc.copy(password = it.trim())) },
        label = { Text("授权码") },
        placeholder = { Text("不是登录密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (showServers) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(acc.imapHost, { save(acc.copy(imapHost = it.trim())) }, label = { Text("IMAP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(acc.imapPort.toString(), { save(acc.copy(imapPort = it.toIntOrNull() ?: acc.imapPort)) },
                label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(acc.smtpHost, { save(acc.copy(smtpHost = it.trim())) }, label = { Text("SMTP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(acc.smtpPort.toString(), { save(acc.copy(smtpPort = it.toIntOrNull() ?: acc.smtpPort)) },
                label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = acc.ready && result != "…", onClick = {
            result = "…"
            scope.launch { result = vm.testMail(acc) }
        }) { Text("测试") }
        if (!showServers) TextButton(onClick = { showServers = true }) { Text("服务器设置") }
        if (mail != null) TextButton(onClick = { save(MailAccount()); vm.updateSettings { it.copy(mail = null) } }) {
            Text("清除", color = MaterialTheme.colorScheme.error)
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
