package com.example.core.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ByeDpiTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (ByeDpiVpnService.isRunning) {
            ByeDpiVpnService.stopService(this)
            updateTileState()
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent == null) {
                ByeDpiVpnService.startService(this)
                updateTileState()
            } else {
                // Needs user confirmation dialog, launch main activity
                val intent = Intent(this, com.example.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = ByeDpiVpnService.isRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ByeByeDPI"
        tile.subtitle = if (isRunning) "Active" else "Off"
        tile.updateTile()
    }
}
