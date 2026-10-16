package com.jack.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(56.dp)
            Spacer(Modifier.height(16.dp))
            Text("Jack AI 已上锁", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Icon(AppIcons.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("解锁")
            }
        }
    }
}
