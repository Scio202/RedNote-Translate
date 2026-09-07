package dev.leo.rednotetrans

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings toggle, so the overlay can be paused without leaving RedNote. */
class TransTile : TileService() {

    override fun onStartListening() = sync()

    override fun onClick() {
        val prefs = Prefs.of(this)
        prefs.enabled = !prefs.enabled
        sync()
    }

    private fun sync() {
        qsTile?.apply {
            state = if (Prefs.of(this@TransTile).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
