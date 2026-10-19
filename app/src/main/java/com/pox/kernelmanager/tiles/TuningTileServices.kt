package com.pox.kernelmanager.tiles

import com.pox.kernelmanager.R
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.repo.TuningApplyResult
import com.pox.kernelmanager.data.repo.TuningRepository

/**
 * One Quick Settings tile per tuning action.
 *
 * Every class here must stay registered in AndroidManifest.xml with the
 * `android.service.quicksettings.action.QS_TILE` intent filter - that is what
 * makes it show up in the Quick Settings editor.
 */

/**
 * Gaming mode tile. Tap cycles the level; long-press opens the picker in
 * [GamingModeTileActivity] (the app's QS_TILE_PREFERENCES activity), because
 * TileService itself has no long-press callback.
 */
class GamingModeTileService : BaseTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_gaming
    override val tileLabel: String get() = getString(R.string.tile_gaming_mode)

    override fun isTileActive(): Boolean =
        TuningRepository.snapshot().gamingMode != GamingModeLevel.BALANCED

    override fun tileDetail(): String = TuningRepository.snapshot().gamingMode.title

    /** Cycles Balanced -> Gaming -> Extreme -> Balanced. */
    override suspend fun onTileClicked(): TuningApplyResult {
        val levels = GamingModeLevel.entries
        val current = TuningRepository.snapshot().gamingMode
        return TuningRepository.setGamingMode(levels[(current.ordinal + 1) % levels.size])
    }
}

class TouchBoostTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_touch
    override val tileLabel: String get() = getString(R.string.tile_touch_boost)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().touchBoost

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult =
        TuningRepository.setTouchBoost(enable)
}

class BypassChargingTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_bypass
    override val tileLabel: String get() = getString(R.string.tile_bypass_charging)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().bypassCharging

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult =
        TuningRepository.setBypassCharging(enable)
}

class ThermalGuardTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_thermal
    override val tileLabel: String get() = getString(R.string.tile_thermal_guard)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().thermalGuard

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult {
        val saved = TuningRepository.snapshot()
        return TuningRepository.setThermalGuard(enable, saved.tempLimitC)
    }
}

class XswapdTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_xswapd
    override val tileLabel: String get() = getString(R.string.tile_xswapd)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().xswapdEnabled

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult =
        TuningRepository.setXswapdEnabled(enable)
}

class DoubleTapToWakeTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_dt2w
    override val tileLabel: String get() = getString(R.string.tile_dt2w)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().doubleTapToWake

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult =
        TuningRepository.setDoubleTapToWake(enable)
}

class DynamicFsyncTileService : ToggleTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_fsync
    override val tileLabel: String get() = getString(R.string.tile_dynamic_fsync)

    override fun isEnabled(): Boolean = TuningRepository.snapshot().dynamicFsync

    override suspend fun setEnabled(enable: Boolean): TuningApplyResult =
        TuningRepository.setDynamicFsync(enable)
}

/** One-shot action: asks xswapd to run a reclaim pass right now. */
class MemoryReclaimTileService : BaseTuningTileService() {

    override val tileIconRes = R.drawable.ic_tile_reclaim
    override val tileLabel: String get() = getString(R.string.tile_memory_reclaim)

    override fun isTileActive(): Boolean = false

    override fun tileDetail(): String = getString(R.string.tile_tap_to_reclaim)

    override suspend fun onTileClicked(): TuningApplyResult =
        TuningRepository.triggerXswapdReclaim()
}
