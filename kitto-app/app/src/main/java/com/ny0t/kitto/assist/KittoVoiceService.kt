package com.ny0t.kitto.assist

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Kitto's assistant service. While Kitto is the chosen assistant (Settings → Assist app), the system
 * keeps this bound, the same way Kitto binds Buckt/Cleo in M1, but here the system is the client.
 *
 * It does nothing itself: on long-press, the system asks [KittoSessionService] for a new
 * [KittoSession] and shows it. Hotwords ("Hey Kitto") would live here, but they're out of scope.
 */
class KittoVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "assistant service ready (Kitto is the assistant)")
    }

    override fun onShutdown() {
        Log.i(TAG, "assistant service shut down (another assistant chosen?)")
        super.onShutdown()
    }
}

internal const val TAG = "KittoAssist"
