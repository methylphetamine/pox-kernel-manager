package com.pox.kernelmanager.tiles

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.R
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.repo.TuningApplyResult
import com.pox.kernelmanager.data.repo.TuningRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Level picker for the Gaming mode tile, opened by long-pressing the tile.
 *
 * Quick Settings has no long-press callback on TileService: SystemUI launches
 * whichever activity in the app declares the [TileService.ACTION_QS_TILE_PREFERENCES]
 * action instead, and falls back to the App info screen when there is none.
 * The intent names the tile that was held in [Intent.EXTRA_COMPONENT_NAME], so
 * the other Pox tiles keep that stock App info behaviour.
 */
class GamingModeTileActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val heldTile = heldTileComponent()
        if (heldTile != null && heldTile.className != GamingModeTileService::class.java.name) {
            // Not the gaming tile: reproduce the default long-press behaviour.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
            finish()
            return
        }

        showPicker()
    }

    @Suppress("DEPRECATION")
    private fun heldTileComponent(): ComponentName? =
        intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME) as? ComponentName

    private fun showPicker() {
        val levels = GamingModeLevel.entries
        val current = TuningRepository.snapshot().gamingMode

        AlertDialog.Builder(this)
            .setTitle(R.string.tile_gaming_mode)
            .setSingleChoiceItems(levels.map { it.title }.toTypedArray(), current.ordinal) { dialog, which ->
                dialog.dismiss()
                apply(levels[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun apply(level: GamingModeLevel) {
        PoxApplication.applicationScope.launch {
            // Wait the process-start restore out first, exactly like the tile
            // does - two interleaved writes would leave the kernel on old values.
            TuningRepository.awaitSavedTuningRestored()
            val result = runCatching { TuningRepository.setGamingMode(level) }
                .getOrElse { TuningApplyResult(false, it.message ?: "Could not set gaming mode") }
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(applicationContext, result.message, Toast.LENGTH_SHORT).show()
                // Refresh the tile now instead of waiting for the panel to reopen.
                TileService.requestListeningState(
                    applicationContext,
                    ComponentName(applicationContext, GamingModeTileService::class.java)
                )
                finish()
            }
        }
    }
}
