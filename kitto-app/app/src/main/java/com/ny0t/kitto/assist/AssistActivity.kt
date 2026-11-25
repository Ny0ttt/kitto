package com.ny0t.kitto.assist

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ny0t.kitto.ChatScreen
import com.ny0t.kitto.ChatViewModel
import com.ny0t.kitto.ui.theme.KittoTheme

/**
 * The assistant sheet: a card at the bottom over whatever app is open. Tap outside to close.
 * Holds the same chat as the main app (streaming, gate dialog). Voice and speech come in Parts C–D.
 */
class AssistActivity : ComponentActivity() {

    // One ViewModel per sheet: each long-press starts a new conversation (M5 decision 5),
    // separate from the main app's chat.
    private val chat: ChatViewModel by viewModels()

    // State, so the card redraws once the time is known.
    private var openMs by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "?"
        val shownAt = intent.getLongExtra(EXTRA_SHOWN_AT, 0L)
        setContent {
            KittoTheme {
                AssistSheet(chat = chat, source = source, openMs = { openMs }, onClose = { finish() })
            }
        }
        window.decorView.post {
            // First frame is on its way: time from session/tile to sheet.
            if (shownAt > 0) openMs = SystemClock.uptimeMillis() - shownAt
            Log.i(TAG, "sheet open via $source in ${openMs ?: -1} ms")
        }
    }

    companion object {
        const val EXTRA_SHOWN_AT = "com.ny0t.kitto.assist.SHOWN_AT"
        const val EXTRA_SOURCE = "com.ny0t.kitto.assist.SOURCE"
    }
}

@Composable
private fun AssistSheet(chat: ChatViewModel, source: String, openMs: () -> Long?, onClose: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    // Full-screen see-through layer: a tap anywhere outside the card closes the sheet.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier
                .fillMaxWidth()
                // The chat list needs a bounded height to scroll inside the card.
                .fillMaxHeight(0.6f)
                // Swallow taps on the card itself so they don't close the sheet.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(top = 16.dp).navigationBarsPadding()) {
                Text(
                    "Kitto · $source" + (openMs()?.let { " · $it ms" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ChatScreen(vm = chat)
            }
        }
    }
}
