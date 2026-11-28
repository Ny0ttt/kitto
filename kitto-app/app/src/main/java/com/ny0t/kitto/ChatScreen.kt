package com.ny0t.kitto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier, listenOnStart: Boolean = false) {
    // M6 B6: no chat (and no listening) until the person has signed in with Google.
    if (!vm.signedIn) {
        SignInPanel(vm, modifier)
        return
    }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Voice: the finished sentence is sent like a typed question.
    val context = LocalContext.current
    val voice = remember { VoiceInput(context) { spoken -> vm.send(spoken, spoken = true) } }
    DisposableEffect(voice) { onDispose { voice.destroy() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else voice.fail("Kitto needs the microphone permission to listen.")
    }
    val listen = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        vm.stopSpeaking() // don't let Kitto's voice talk over the mic
        if (granted) voice.start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { if (listenOnStart) listen() }

    // Keep the newest message in view.
    LaunchedEffect(vm.lines.size) {
        if (vm.lines.isNotEmpty()) listState.animateScrollToItem(vm.lines.size - 1)
    }

    Column(modifier.fillMaxSize().imePadding().padding(16.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.lines) { line -> ChatBubble(line) }
        }

        // Voice problems and "listening…" come first, then the chat's own status, then speaking.
        val speakingNow = vm.speaking && !voice.listening && vm.status == null && voice.error == null
        (voice.error ?: (if (voice.listening) "listening…" else null) ?: vm.status
            ?: (if (speakingNow) "speaking… tap to stop" else null))?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .then(if (speakingNow) Modifier.clickable { vm.stopSpeaking() } else Modifier),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                // While listening, the box shows what the recognizer has heard so far.
                value = if (voice.listening) voice.partial else input,
                onValueChange = { input = it },
                placeholder = { Text(if (voice.listening) "Listening…" else "Ask Kitto") },
                enabled = !vm.busy && !voice.listening,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !vm.busy,
                onClick = { if (voice.listening) voice.stop() else listen() },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = if (voice.listening) "Stop listening" else "Speak",
                    tint = if (voice.listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Button(
                enabled = !vm.busy && input.isNotBlank(),
                onClick = {
                    vm.send(input)
                    input = ""
                },
            ) { Text("Send") }
        }
    }

    vm.gateRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { vm.answerGate(false) },
            title = { Text("Allow this action?") },
            text = { Text(gateText(request)) },
            confirmButton = { TextButton(onClick = { vm.answerGate(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.answerGate(false) }) { Text("Deny") } },
        )
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(line.text, modifier = Modifier.padding(12.dp))
        }
    }
}

/** First-open screen: Kitto asks the backend as this Google account, which must be on the allowlist. */
@Composable
private fun SignInPanel(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.findActivity()
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sign in to use Kitto", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kitto uses your Google account to know it's you. Only accounts Ny0t has added can ask questions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(enabled = !vm.signingIn && activity != null, onClick = { activity?.let(vm::signIn) }) {
            Text(if (vm.signingIn) "Signing in…" else "Sign in with Google")
        }
        vm.signInError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
