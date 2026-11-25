package com.ny0t.kitto.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * One long-press. The system gives a session its own window above every app; Kitto leaves that window
 * empty and opens [AssistActivity] instead, so the UI stays plain Compose (decision 1).
 *
 * MIUI blocks this activity start unless Kitto has "Display pop-up windows while running in the
 * background" (app op 10021); without it you get the glow border and nothing else (pre-check 2026-11-25).
 */
class KittoSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val shownAt = SystemClock.uptimeMillis()
        Log.i(TAG, "session shown flags=$showFlags")
        startAssistantActivity(
            Intent(context, AssistActivity::class.java)
                .putExtra(AssistActivity.EXTRA_SHOWN_AT, shownAt)
                .putExtra(AssistActivity.EXTRA_SOURCE, "assistant"),
        )
        // The sheet is the activity; the session's own (empty, full-screen) window would only sit on
        // top and swallow taps, so put it away. Spike check: if this also closes the activity on MIUI,
        // remove this line and hide from AssistActivity.onDestroy instead.
        hide()
    }

    override fun onHide() {
        Log.i(TAG, "session hidden")
        super.onHide()
    }
}
