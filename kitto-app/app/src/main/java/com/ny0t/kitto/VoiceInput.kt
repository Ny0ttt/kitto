package com.ny0t.kitto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val TAG = "KittoVoice"

/**
 * Google's speech recognizers, in order of preference. Never the system default: once Kitto is the
 * assistant, the default may point at Kitto's own placeholder service (M5 decision 2).
 */
private val GOOGLE_RECOGNIZERS = listOf(
    "com.google.android.googlequicksearchbox", // Google app
    "com.google.android.tts",                  // Speech Services by Google
)

/** Finds a Google recognizer by package name, or null if none is installed. */
fun findGoogleRecognizer(context: Context): ComponentName? {
    val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
    return GOOGLE_RECOGNIZERS.firstNotNullOfOrNull { pkg ->
        services.firstOrNull { it.serviceInfo.packageName == pkg }
            ?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }
}

/**
 * Speech to text through Google's recognizer. Compose reads [listening], [partial] and [error];
 * the finished sentence goes to [onFinal]. Create, call and [destroy] it on the main thread.
 */
class VoiceInput(context: Context, private val onFinal: (String) -> Unit) {

    var listening by mutableStateOf(false)
        private set
    /** What the recognizer has heard so far, shown live while the user speaks. */
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // True from start() until the first result or error. Google's recognizer can send a late
    // error after the result; anything that arrives while inactive is ignored.
    private var active = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "ready for speech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (active) partial = firstResult(partialResults)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            finish()
            val text = firstResult(results).trim()
            Log.d(TAG, "result: \"$text\"")
            if (text.isEmpty()) error = "Didn't catch that. Tap the mic to try again." else onFinal(text)
        }

        override fun onError(code: Int) {
            Log.d(TAG, "error $code (active=$active)")
            if (!active) return
            finish()
            error = errorText(code)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val component = findGoogleRecognizer(context)
    private val recognizer: SpeechRecognizer? = component?.let {
        Log.i(TAG, "using recognizer ${it.flattenToShortString()}")
        SpeechRecognizer.createSpeechRecognizer(context, it).apply { setRecognitionListener(listener) }
    }

    fun start() {
        val r = recognizer ?: return fail("No Google speech service found. Type your question instead.")
        if (active) return
        error = null
        partial = ""
        active = true
        listening = true
        // No language extra: the phone's default language (decision 2).
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    /** Stops listening; the recognizer still delivers what it heard so far as the result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun fail(message: String) {
        finish()
        error = message
    }

    fun destroy() {
        active = false
        recognizer?.destroy()
    }

    private fun finish() {
        active = false
        listening = false
        partial = ""
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Didn't catch that. Tap the mic to try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Kitto needs the microphone permission to listen."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech needs an internet connection. Type your question instead."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available right now."
        else -> "Speech error ($code). Type your question instead."
    }
}
