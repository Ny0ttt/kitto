package com.ny0t.kitto

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.ny0t.kitto.ui.theme.KittoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Built once, on first use. Holds an OkHttp connection pool, so don't rebuild it per call.
    private val client by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(BuildConfig.ANTHROPIC_API_KEY)
            .build()
    }

    // Network call, so it runs on the IO dispatcher, never the main thread.
    private suspend fun askClaude(prompt: String): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model("claude-haiku-5-5")
            .maxTokens(1024L)
            .addUserMessage(prompt)
            .build()
        val response = client.messages().create(params)
        Log.d("Kitto", "stop=${response.stopReason()} usage=${response.usage()}")
        response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KittoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpikeScreen(
                        ask = ::askClaude,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun SpikeScreen(ask: suspend (String) -> String, modifier: Modifier = Modifier) {
    var reply by remember { mutableStateOf("Tap the button.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.padding(16.dp)) {
        Button(enabled = !busy, onClick = {
            busy = true
            reply = "Asking Claude…"
            scope.launch {
                reply = try {
                    ask("Say hi to Ny0t in one short sentence.")
                } catch (t: Throwable) {
                    // Throwable on purpose: missing-class errors (desugaring/Jackson) are Errors, not Exceptions
                    Log.e("Kitto", "Claude call failed", t)
                    "Error: ${t::class.simpleName}: ${t.message}"
                }
                busy = false
            }
        }) { Text("Ask Claude") }

        Spacer(Modifier.height(16.dp))
        Text(reply)
    }
}
