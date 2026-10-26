package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jack.ai.ChatViewModel
import com.jack.ai.data.PhoneFiles
import com.jack.ai.data.PhoneFolder
import com.jack.ai.data.Settings

/** 设置页的「手机文件夹」：用系统的文件选择器授权文件夹，列出已授权的，能取消。 */
@Composable
fun PhoneFoldersSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            PhoneFiles.keep(ctx, uri)
            var name = PhoneFiles.nameOf(ctx, uri)
            // 两个文件夹同名（比如两个「Download」）：后一个加编号，路径才分得开
            val taken = settings.phoneFolders.map { it.name }.toSet()
            if (name in taken) name = (2..99).map { "$name$it" }.first { it !in taken }
            vm.updateSettings { s -> if (s.phoneFolders.any { it.uri == uri.toString() }) s else s.copy(phoneFolders = s.phoneFolders + PhoneFolder(uri.toString(), name)) }
        } catch (e: Exception) {
            Toast.makeText(ctx, "没授权成功：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    val alive = remember(settings.phoneFolders) { settings.phoneFolders.associate { it.uri to PhoneFiles.alive(ctx, it) } }
    Column {
        if (settings.phoneFolders.isEmpty()) Text("还没有授权文件夹。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        settings.phoneFolders.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name)
                    if (alive[f.uri] != true) Text("授权失效了，删掉重新添加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    PhoneFiles.release(ctx, f.uri)
                    vm.updateSettings { s -> s.copy(phoneFolders = s.phoneFolders.filterNot { it.uri == f.uri }) }
                }) { Text("取消授权") }
            }
        }
        OutlinedButton(onClick = { runCatching { pick.launch(null) } }) { Text("添加文件夹") }
    }
}
