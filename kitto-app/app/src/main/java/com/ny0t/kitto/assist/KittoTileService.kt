package com.ny0t.kitto.assist

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Kitto": swipe down, tap, and the sheet opens. Kitto's own way in, so it works
 * whoever the assistant is and needs no MIUI background permission (a tap counts as the user starting it).
 */
class KittoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE // a button, not an on/off toggle
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistActivity.EXTRA_SHOWN_AT, SystemClock.uptimeMillis())
            .putExtra(AssistActivity.EXTRA_SOURCE, "tile")
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ only accepts a PendingIntent here.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
