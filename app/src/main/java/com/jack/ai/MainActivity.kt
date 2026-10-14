package com.jack.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jack.ai.ui.ChatScreen
import com.jack.ai.ui.JackTheme
import com.jack.ai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(state.settings.apiKey.isBlank()) }
            JackTheme(state.settings.themeMode) {
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(vm = vm, settings = state.settings, onBack = { showSettings = false })
                } else {
                    ChatScreen(vm = vm, state = state, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
