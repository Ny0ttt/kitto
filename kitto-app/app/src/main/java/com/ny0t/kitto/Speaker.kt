package com.ny0t.kitto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val TAG = "KittoSpeak"

/**
 * Speaks replies through the phone's system text-to-speech engine (the same one TalkBack and Maps
 * use; not an accessibility service). The engine starts in the background, so text sent before it
 * is ready waits and is spoken once it is. Call [shutdown] when done.
 */
class Speaker(context: Context) {

    /** True while something is being spoken, so the screen can offer "tap to stop". */
    var speaking by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            Log.i(TAG, "engine ${tts.defaultEngine}, voice ${tts.voice?.name}")
            pending?.let { say(it) }
        } else {
            Log.w(TAG, "text-to-speech failed to start ($status)")
        }
        pending = null
    }

    init {
        // Progress callbacks arrive on a background thread; state changes go to the main thread.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { speaking = true } }
            override fun onDone(utteranceId: String?) { main.post { speaking = false } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { speaking = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { speaking = false } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speech error $errorCode")
                main.post { speaking = false }
            }
        })
    }

    /** Speaks [text], replacing anything still being spoken. */
    fun say(text: String) {
        val clean = speakable(text)
        if (clean.isBlank()) return
        if (!ready) {
            pending = clean
            return
        }
        Log.d(TAG, "speaking ${clean.length} chars")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "kitto-reply")
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        speaking = false
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/**
 * Removes markdown marks the engine would read out or stumble on (**bold**, `code`, # headings,
 * list dashes). Underscores stay: they are part of names more often than markdown. Claude's replies are short, plain sentences, so this stays simple.
 */
internal fun speakable(text: String): String =
    text.lines()
        .map { line -> line.trim().removePrefix("#").trimStart('#', ' ').let { stripBullet(it) } }
        .joinToString("\n")
        .replace(Regex("[*`]+"), "")
        .trim()

private fun stripBullet(line: String): String =
    if (line.startsWith("- ") || line.startsWith("• ")) line.substring(2) else line
