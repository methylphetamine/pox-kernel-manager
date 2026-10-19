package com.pox.kernelmanager.tiles

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.R
import com.pox.kernelmanager.data.repo.TuningApplyResult
import com.pox.kernelmanager.data.repo.TuningRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared plumbing for the tuning quick settings tiles.
 *
 * Tiles render from the values persisted in app storage rather than from the
 * kernel, so the panel reacts instantly even when a root shell is slow - the
 * repository keeps both in sync on every change.
 */
abstract class BaseTuningTileService : TileService() {

    @get:DrawableRes
    protected abstract val tileIconRes: Int

    /** Primary label, fixed for the lifetime of the tile. */
    protected abstract val tileLabel: String

    /** Whether the feature is currently on. Drives the tile highlight. */
    protected abstract fun isTileActive(): Boolean

    /** Secondary label under the tile title; only shown on Android 10+. */
    protected open fun tileDetail(): String? = null

    /** Applies the tap. Runs on a background thread. */
    protected abstract suspend fun onTileClicked(): TuningApplyResult

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render()
    }

    override fun onClick() {
        super.onClick()
        // The service can be destroyed the moment the panel closes, so the write
        // runs on the process-wide scope instead: a cancelled half-applied
        // change would be worse than the tiny lifetime it costs.
        PoxApplication.applicationScope.launch {
            // Wait out the process-start restore before flipping a value, or the
            // two could interleave and leave the kernel on the old setting.
            TuningRepository.awaitSavedTuningRestored()
            val result = runCatching { onTileClicked() }
                .getOrElse { TuningApplyResult(false, it.message ?: "Tile action failed") }
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(applicationContext, result.message, Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    /** Push the current state into Quick Settings. Safe to call at any time. */
    protected fun render() {
        val tile = qsTile ?: return
        tile.state = if (isTileActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = tileLabel
        tile.icon = Icon.createWithResource(this, tileIconRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = tileDetail()
        }
        tile.updateTile()
    }
}

/** Convenience base for the plain on/off features. */
abstract class ToggleTuningTileService : BaseTuningTileService() {

    protected abstract fun isEnabled(): Boolean

    protected abstract suspend fun setEnabled(enable: Boolean): TuningApplyResult

    final override fun isTileActive(): Boolean = isEnabled()

    final override fun tileDetail(): String =
        getString(if (isEnabled()) R.string.tile_state_on else R.string.tile_state_off)

    final override suspend fun onTileClicked(): TuningApplyResult = setEnabled(!isEnabled())
}
