package com.minisx.app.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A quick-settings tile that calls the assistant: works without MinisX being the default assistant, so
 * it is the way in for a phone whose side key or power button cannot be reassigned.
 */
class AssistantTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_DELAY_MS, 450L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
