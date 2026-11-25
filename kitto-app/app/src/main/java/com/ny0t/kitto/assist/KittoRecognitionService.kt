package com.ny0t.kitto.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Placeholder. kitto_voice.xml must name a recognition service or Android won't offer Kitto as an
 * assistant, but Kitto never listens through this: it binds Google's recognizer by name (decision 2).
 * If something else ends up here (the system default may point at this service once Kitto is the
 * assistant, see device test 10), it gets a clean error instead of hanging.
 */
class KittoRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.w(TAG, "recognition requested from the placeholder service; returning an error")
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
