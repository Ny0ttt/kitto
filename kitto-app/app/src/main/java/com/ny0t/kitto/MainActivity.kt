package com.ny0t.kitto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.ny0t.kitto.ui.theme.KittoTheme

class MainActivity : ComponentActivity() {

    // Survives rotation; the chat is lost only when the app process dies.
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KittoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // consumeWindowInsets: the nav bar is already padded, so imePadding
                    // inside ChatScreen must not add it again above the keyboard.
                    ChatScreen(
                        vm = chat,
                        modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    )
                }
            }
        }
    }
}
