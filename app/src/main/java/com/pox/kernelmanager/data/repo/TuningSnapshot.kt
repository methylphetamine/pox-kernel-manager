package com.pox.kernelmanager.data.repo

import com.pox.kernelmanager.data.model.GamingModeLevel

/**
 * Everything the user can tune, exactly as last chosen in the app.
 *
 * The kernel side of these settings lives in volatile nodes that reset on every
 * reboot, and a root shell may be denied right after one, so each change is
 * also written to SharedPreferences. [TuningRepository.reapplySaved] replays
 * this snapshot into the kernel after a reboot or a force stop.
 */
data class TuningSnapshot(
    val gamingMode: GamingModeLevel = GamingModeLevel.BALANCED,
    val touchBoost: Boolean = false,
    val bypassCharging: Boolean = false,
    val chargeLimitPercent: Int = 100,
    val thermalGuard: Boolean = false,
    val tempLimitC: Int = 48,
    val xswapdEnabled: Boolean = false,
    val doubleTapToWake: Boolean = false,
    val vibratorStrengthPercent: Int = 85,
    val dynamicFsync: Boolean = false,
    val readAheadKb: Int = 256,
    val ioScheduler: String? = null,
    val cpuGovernor: String? = null,
    val zramSizeMb: Long? = null,
    val zramCompAlgorithm: String? = null,
    val zramBackingEnabled: Boolean = false,
    val zramBackingSizeMb: Long? = null,
    val zramWritebackLimitMb: Long = 0,
    val zramWritebackLimitEnabled: Boolean = false
)
