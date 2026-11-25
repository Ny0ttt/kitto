package com.ny0t.kitto.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** The system calls this once per long-press to get a fresh session. */
class KittoSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = KittoSession(this)
}
