package com.pox.kernelmanager.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.model.TuningFeature
import com.pox.kernelmanager.data.model.TuningFeatures
import com.pox.kernelmanager.data.model.ZramWritebackMode
import com.pox.kernelmanager.data.root.KernelNodes
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class TuningApplyResult(
    val isHardwareApplied: Boolean,
    val message: String
)

object TuningRepository {

    private val prefs: SharedPreferences by lazy {
        PoxApplication.instance.getSharedPreferences("pox_tuning_prefs", Context.MODE_PRIVATE)
    }

    // Preference keys. The first dozen existed before persistence was taken
    // seriously and keep their historical names so upgrades do not lose values.
    private const val KEY_HAS_SAVED_STATE = "has_saved_state"
    private const val KEY_GAMING_MODE = "gaming_mode"
    private const val KEY_TOUCH_BOOST = "touch_boost"
    private const val KEY_BYPASS_CHARGING = "bypass_charging"
    private const val KEY_FAST_CHARGE = "fast_charge"
    private const val KEY_CHARGE_LIMIT = "charge_limit"
    private const val KEY_THERMAL_GUARD = "thermal_guard"
    private const val KEY_TEMP_LIMIT = "temp_limit"
    private const val KEY_XSWAPD_ENABLE = "xswapd_enable"
    private const val KEY_XSWAPD_QUOTA = "xswapd_quota"
    private const val KEY_DT2W = "dt2w"
    private const val KEY_VIBRATOR_STRENGTH = "vibrator_strength"
    private const val KEY_DYNAMIC_FSYNC = "dynamic_fsync"
    private const val KEY_READ_AHEAD_KB = "read_ahead_kb"
    private const val KEY_IO_SCHEDULER = "io_scheduler"
    private const val KEY_CPU_GOVERNOR = "cpu_governor"
    private const val KEY_ZRAM_SIZE_MB = "zram_size_mb"
    private const val KEY_ZRAM_COMP_ALGORITHM = "zram_comp_algorithm"
    private const val KEY_ZRAM_BACKING_ENABLED = "zram_backing_enabled"
    private const val KEY_ZRAM_BACKING_SIZE_MB = "zram_backing_size_mb"
    private const val KEY_ZRAM_WRITEBACK_LIMIT_MB = "zram_writeback_limit_mb"
    private const val KEY_ZRAM_WRITEBACK_LIMIT_ENABLED = "zram_writeback_limit_enabled"
    private const val KEY_RESTORE_ATTEMPTS = "restore_attempts"

    /**
     * EAS boost applied per gaming level.
     *
     * `eas_ctrl.c` clamps through `MIN_BOOST_VALUE`/`MAX_BOOST_VALUE`, both -100
     * and 100, so these stay inside the range the kernel accepts.
     */
    private val boostForMode = mapOf(
        GamingModeLevel.BALANCED to 0,
        GamingModeLevel.GAMING to 50,
        GamingModeLevel.EXTREME to 100
    )

    /**
     * Write a set of kernel nodes and report what actually happened.
     *
     * [writes] is a list of path/value pairs; the call succeeds when at least one
     * of them lands, because several of these features are reachable through more
     * than one node.
     *
     * Every setter used to ignore `fastWrite` and return success unconditionally,
     * so a missing `/proc/perfmgr` node was indistinguishable from a working
     * write. Nothing here targets a node this kernel does not build.
     */
    private suspend fun applyKernelWrites(
        successLabel: String,
        vararg writes: Pair<String, String>
    ): TuningApplyResult {
        val accepted = writes.filter { ShellUtils.fastWrite(it.first, it.second) }.map { it.first }.toSet()
        val rejected = writes.map { it.first }.filterNot { it in accepted }

        return when {
            accepted.isNotEmpty() -> TuningApplyResult(true, successLabel)
            else -> TuningApplyResult(
                false,
                "Kernel rejected the write - none of ${writes.joinToString { it.first }} are writable"
            )
        }
    }

    /** Nothing in pox-begonia-kernel implements this, so say so instead of writing. */
    private fun unsupported(feature: TuningFeature): TuningApplyResult =
        TuningApplyResult(false, "${feature.label} has no interface in pox-begonia-kernel")

    private suspend fun isSupported(feature: TuningFeature): Boolean =
        KernelNodes.support()[feature] == true

    suspend fun getTuningFeatures(): TuningFeatures = withContext(Dispatchers.IO) {
        val isRoot = ShellUtils.isRootAvailable()
        val rootEnv = ShellUtils.environment()
        val support = KernelNodes.support()

        val savedGaming = prefs.getInt(KEY_GAMING_MODE, 0)
        val gamingRaw = if (isRoot) readGamingRaw() ?: savedGaming else savedGaming

        val gamingLevel = gamingLevelOf(gamingRaw)

        val touchBoost = if (isRoot) {
            KernelNodes.parseUsrtchField(
                ShellUtils.readNode(KernelNodes.USRTCH).orEmpty(),
                "enable"
            )?.let { it == 1 } ?: prefs.getBoolean(KEY_TOUCH_BOOST, false)
        } else prefs.getBoolean(KEY_TOUCH_BOOST, false)

        // Battery guard & charging controls
        val bypass = if (isRoot && isSupported(TuningFeature.BYPASS_CHARGING)) {
            ShellUtils.fastRead(KernelNodes.BATTERY_BYPASS_PROC) == "1" ||
            ShellUtils.fastRead(KernelNodes.BATTERY_BYPASS_SYS) == "1" ||
            ShellUtils.fastRead(KernelNodes.BATTERY_CHARGING_ENABLED) == "0"
        } else prefs.getBoolean(KEY_BYPASS_CHARGING, false)

        val fastCharge = if (isRoot && isSupported(TuningFeature.FAST_CHARGE)) {
            ShellUtils.fastRead(KernelNodes.FAST_CHARGE_PROC) == "1"
        } else prefs.getBoolean(KEY_FAST_CHARGE, true)

        val chargeLimit = if (isRoot && isSupported(TuningFeature.CHARGE_LIMIT)) {
            ShellUtils.fastRead(KernelNodes.BATTERY_LIMIT_PROC)?.toIntOrNull()
                ?: ShellUtils.fastRead(KernelNodes.BATTERY_LIMIT_SYS)?.toIntOrNull()
                ?: ShellUtils.fastRead(KernelNodes.BATTERY_CHARGE_CONTROL_LIMIT)?.toIntOrNull()
                ?: prefs.getInt(KEY_CHARGE_LIMIT, 100)
        } else prefs.getInt(KEY_CHARGE_LIMIT, 100)

        val thermalGuard = if (isRoot && isSupported(TuningFeature.THERMAL_GUARD)) {
            ShellUtils.fastRead(KernelNodes.THERMAL_GUARD_SYS) == "1"
        } else prefs.getBoolean(KEY_THERMAL_GUARD, false)

        val tempLimit = if (isRoot && isSupported(TuningFeature.THERMAL_GUARD)) {
            ShellUtils.fastRead(KernelNodes.TEMP_LIMIT_SYS)?.toIntOrNull()
                ?: prefs.getInt(KEY_TEMP_LIMIT, 48)
        } else prefs.getInt(KEY_TEMP_LIMIT, 48)

        // xswapd is a MIUI/HyperOS userspace daemon.
        val xswapdEnable = if (isRoot && isSupported(TuningFeature.XSWAPD)) {
            ShellUtils.fastRead("/dev/memcg/memory.xswapd.enable") != "0"
        } else prefs.getBoolean(KEY_XSWAPD_ENABLE, false)

        val savedXswapdQuota = prefs.getLong(KEY_XSWAPD_QUOTA, 1024L)
        val xswapdQuota = if (isRoot && isSupported(TuningFeature.XSWAPD)) {
            (ShellUtils.fastRead("/dev/memcg/memory.xswapd.quota")?.toLongOrNull()
                ?: (savedXswapdQuota * 1024 * 1024)) / (1024 * 1024)
        } else savedXswapdQuota

        // Touch, display and haptics
        val dt2w = if (isRoot && isSupported(TuningFeature.DT2W)) {
            ShellUtils.fastRead(KernelNodes.DT2W_PROC) == "1"
        } else prefs.getBoolean(KEY_DT2W, false)

        val vibrator = if (isRoot && isSupported(TuningFeature.VIBRATOR_STRENGTH)) {
            val level = ShellUtils.fastRead(KernelNodes.VIBRATOR_STRENGTH_PROC)?.toIntOrNull()
                ?: ShellUtils.fastRead(KernelNodes.VIBRATOR_VMAX)?.toIntOrNull()
            if (level != null) (level * 100 / 6).coerceIn(0, 100) else prefs.getInt(KEY_VIBRATOR_STRENGTH, 85)
        } else prefs.getInt(KEY_VIBRATOR_STRENGTH, 85)

        val fsync = if (isRoot && isSupported(TuningFeature.DYNAMIC_FSYNC)) {
            ShellUtils.fastRead(KernelNodes.DYNAMIC_FSYNC_PROC) == "1" ||
            ShellUtils.fastRead(KernelNodes.DYNAMIC_FSYNC_SYS) == "1"
        } else prefs.getBoolean(KEY_DYNAMIC_FSYNC, false)

        val readAhead = if (isRoot) {
            val queues = KernelNodes.storageDevices().map { "$it/queue" }.ifEmpty { blockQueues() }
            queues.firstNotNullOfOrNull { ShellUtils.readNode("$it/read_ahead_kb") }?.toIntOrNull()
                ?: prefs.getInt(KEY_READ_AHEAD_KB, 256)
        } else prefs.getInt(KEY_READ_AHEAD_KB, 256)

        // Prefer a physical queue. zram/loop only advertise "none" and used to
        // win the firstNotNullOfOrNull race, hiding the schedulers UFS supports.
        val schedulerCandidates = KernelNodes.storageDevices().map { "$it/queue" }
            .ifEmpty { blockQueues() }
        val schedulerProbe = schedulerCandidates.firstNotNullOfOrNull { queue ->
            ShellUtils.readNode("$queue/scheduler")?.let { queue to it }
        }
        val schedulerRaw = schedulerProbe?.second.orEmpty()
        val (schedulerFromKernel, schedulerOptions) = KernelNodes.parseSelection(schedulerRaw)
        // The kernel resets queue/scheduler on reboot; fall back to the saved
        // choice so the selector still shows what the user picked.
        val activeScheduler = schedulerFromKernel.ifEmpty { prefs.getString(KEY_IO_SCHEDULER, null).orEmpty() }
        val schedulerQueue = schedulerProbe?.first
            ?.removeSuffix("/queue")
            ?.substringAfterLast('/')
            .orEmpty()

        val governorRaw = KernelNodes.cpufreqPolicies()
            .firstNotNullOfOrNull { ShellUtils.readNode("$it/cpufreq/scaling_governor") }
            .orEmpty()
        val (governorFromKernel, governorOptions) = KernelNodes.parseSelection(governorRaw)
        val activeGovernor = governorFromKernel.ifEmpty { prefs.getString(KEY_CPU_GOVERNOR, null).orEmpty() }

        val zramSize = ShellUtils.readNode(KernelNodes.ZRAM_DISKSIZE)
            ?.trim()?.toLongOrNull()?.div(1024 * 1024)?.takeIf { it > 0 }
            ?: prefs.getLong(KEY_ZRAM_SIZE_MB, 0L)

        val zramCompRaw = ShellUtils.readNode(KernelNodes.ZRAM_COMP_ALGORITHM).orEmpty()
        val (compFromKernel, compOptions) = KernelNodes.parseSelection(zramCompRaw)
        val zramComp = compFromKernel.ifEmpty {
            prefs.getString(KEY_ZRAM_COMP_ALGORITHM, null).orEmpty()
        }

        val backingRaw = ShellUtils.fastRead(KernelNodes.ZRAM_BACKING_DEV)?.trim().orEmpty()
        val backingEnabled = backingRaw.isNotEmpty() && !backingRaw.equals("none", ignoreCase = true)
        val savedBackingSize = prefs.getLong(KEY_ZRAM_BACKING_SIZE_MB, 0L)
        val backingSize = if (backingEnabled) {
            blockDeviceSizeMb(backingRaw) ?: savedBackingSize
        } else {
            savedBackingSize
        }

        val writebackLimitUnits = ShellUtils.fastRead(KernelNodes.ZRAM_WRITEBACK_LIMIT)
            ?.trim()?.toLongOrNull() ?: 0L
        val writebackLimitEnabled =
            ShellUtils.fastRead(KernelNodes.ZRAM_WRITEBACK_LIMIT_ENABLE)?.trim() == "1"
        val writebackMb = parseBdStat(ShellUtils.fastRead(KernelNodes.ZRAM_BD_STAT))?.third?.div(256) ?: 0L

        TuningFeatures(
            gamingMode = gamingLevel,
            touchBoost240Hz = touchBoost,
            bypassCharging = bypass,
            fastChargeEnabled = fastCharge,
            chargeLimitPercent = chargeLimit,
            thermalGuardEnabled = thermalGuard,
            tempLimitC = tempLimit,
            xswapdEnabled = xswapdEnable,
            xswapdQuotaMb = xswapdQuota,
            doubleTapToWake = dt2w,
            vibratorStrengthPercent = vibrator,
            dynamicFsync = fsync,
            readAheadKb = readAhead,
            ioScheduler = activeScheduler,
            availableSchedulers = schedulerOptions,
            schedulerQueue = schedulerQueue,
            cpuGovernor = activeGovernor,
            availableGovernors = governorOptions,
            zramSizeMb = zramSize,
            zramCompAlgorithm = zramComp,
            availableZramAlgorithms = compOptions,
            zramBackingEnabled = backingEnabled,
            zramBackingSizeMb = backingSize,
            zramBackingDevice = if (backingEnabled) backingRaw else "",
            zramWritebackLimitMb = writebackLimitUnits / 256,
            zramWritebackLimitEnabled = writebackLimitEnabled,
            zramWritebackMb = writebackMb,
            rootGranted = isRoot,
            rootProviderLabel = rootEnv.provider.displayName,
            unsupported = support.filterValues { !it }.keys
        )
    }

    // ---- Persistence & boot restore ----

    /**
     * Persist a preference change synchronously.
     *
     * `apply()` returns before the value reaches disk, so a force stop (or an
     * abrupt reboot) in that window could lose the write. Every change also
     * marks the store as "has saved state", which is what allows the boot
     * receiver and the app itself to replay it later.
     */
    private fun persist(block: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
        runCatching {
            prefs.edit()
                .putBoolean(KEY_HAS_SAVED_STATE, true)
                .block()
                .commit()
        }
    }

    /** Whether the user ever changed something worth restoring. */
    fun hasSavedState(): Boolean = runCatching { prefs.getBoolean(KEY_HAS_SAVED_STATE, false) }
        .getOrDefault(false)

    private fun gamingLevelOf(raw: Int): GamingModeLevel = when (raw) {
        1 -> GamingModeLevel.GAMING
        2 -> GamingModeLevel.EXTREME
        else -> GamingModeLevel.BALANCED
    }

    /** `/proc/perfmgr/gaming_mode` reports the live state as `gaming_mode: N`. */
    private val gamingProcLine = Regex("gaming_mode:\\s*(-?\\d+)")

    /**
     * The level the kernel is actually running.
     *
     * The onyx builds report the live state in `/proc/perfmgr/gaming_mode`
     * (first line `gaming_mode: N`), with `/sys/kernel/gaming_mode` as an int
     * mirror. The EAS boost heuristic only serves older builds without either
     * node.
     */
    private suspend fun readGamingRaw(): Int? {
        gamingProcLine.find(ShellUtils.fastRead(KernelNodes.GAMING_MODE_PROC).orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        ShellUtils.fastRead(KernelNodes.GAMING_MODE_SYS)?.trim()?.toIntOrNull()?.let { return it }

        val boost = ShellUtils.readNode(KernelNodes.BOOST_FG)?.trim()?.toIntOrNull() ?: return null
        return boostForMode.entries.minByOrNull { kotlin.math.abs(it.value - boost) }?.key?.value
    }

    /** The last choices made in the app, read straight from app storage. */
    fun snapshot(): TuningSnapshot = TuningSnapshot(
        gamingMode = gamingLevelOf(prefs.getInt(KEY_GAMING_MODE, 0)),
        touchBoost = prefs.getBoolean(KEY_TOUCH_BOOST, false),
        bypassCharging = prefs.getBoolean(KEY_BYPASS_CHARGING, false),
        fastChargeEnabled = prefs.getBoolean(KEY_FAST_CHARGE, true),
        chargeLimitPercent = prefs.getInt(KEY_CHARGE_LIMIT, 100),
        thermalGuard = prefs.getBoolean(KEY_THERMAL_GUARD, false),
        tempLimitC = prefs.getInt(KEY_TEMP_LIMIT, 48),
        xswapdEnabled = prefs.getBoolean(KEY_XSWAPD_ENABLE, false),
        doubleTapToWake = prefs.getBoolean(KEY_DT2W, false),
        vibratorStrengthPercent = prefs.getInt(KEY_VIBRATOR_STRENGTH, 85),
        dynamicFsync = prefs.getBoolean(KEY_DYNAMIC_FSYNC, false),
        readAheadKb = prefs.getInt(KEY_READ_AHEAD_KB, 256),
        ioScheduler = prefs.getString(KEY_IO_SCHEDULER, null)?.takeIf { it.isNotBlank() },
        cpuGovernor = prefs.getString(KEY_CPU_GOVERNOR, null)?.takeIf { it.isNotBlank() },
        zramSizeMb = prefs.getLong(KEY_ZRAM_SIZE_MB, 0L).takeIf { it > 0 },
        zramCompAlgorithm = prefs.getString(KEY_ZRAM_COMP_ALGORITHM, null)?.takeIf { it.isNotBlank() },
        zramBackingEnabled = prefs.getBoolean(KEY_ZRAM_BACKING_ENABLED, false),
        zramBackingSizeMb = prefs.getLong(KEY_ZRAM_BACKING_SIZE_MB, 0L).takeIf { it > 0 },
        zramWritebackLimitMb = prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L),
        zramWritebackLimitEnabled = prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false)
    )

    /**
     * Replays the saved choices into the kernel.
     *
     * The tuning nodes are volatile: after a reboot every one of them holds its
     * default, which is what made the app look like it had forgotten everything.
     * Running this after boot (and once per app process) puts the device back in
     * the state the user chose.
     */
    suspend fun reapplySaved(): TuningApplyResult = withContext(Dispatchers.IO) {
        if (!hasSavedState()) {
            return@withContext TuningApplyResult(false, "No saved tuning to restore")
        }
        if (!ShellUtils.isRootAvailable()) {
            return@withContext TuningApplyResult(false, "Saved tuning kept - root is unavailable right now")
        }

        val saved = snapshot()
        var attempted = 0
        var applied = 0

        suspend fun step(result: TuningApplyResult) {
            attempted++
            if (result.isHardwareApplied) applied++
        }

        // Gaming mode writes touch boost itself, so restore it first and let the
        // explicit touch-boost choice override whatever the level decided.
        step(setGamingMode(saved.gamingMode))
        step(setTouchBoost(saved.touchBoost))
        step(setBypassCharging(saved.bypassCharging))
        step(setFastCharge(saved.fastChargeEnabled))
        step(setChargeLimit(saved.chargeLimitPercent))
        step(setThermalGuard(saved.thermalGuard, saved.tempLimitC))
        step(setXswapdEnabled(saved.xswapdEnabled))
        step(setDoubleTapToWake(saved.doubleTapToWake))
        step(setVibratorStrength(saved.vibratorStrengthPercent))
        step(setDynamicFsync(saved.dynamicFsync))
        step(setReadAheadKb(saved.readAheadKb))
        saved.ioScheduler?.let { step(setIoScheduler(it)) }
        saved.cpuGovernor?.let { step(setCpuGovernor(it)) }
        if (saved.zramSizeMb != null || saved.zramBackingEnabled) {
            val size = saved.zramSizeMb ?: currentZramSizeMb() ?: 2048L
            step(
                applyZramConfiguration(
                    sizeMb = size,
                    backingEnabled = saved.zramBackingEnabled,
                    backingSizeMb = saved.zramBackingSizeMb ?: size,
                    compAlgorithm = saved.zramCompAlgorithm,
                    writebackLimitMb = saved.zramWritebackLimitMb,
                    writebackLimitEnabled = saved.zramWritebackLimitEnabled
                )
            )
        }

        TuningApplyResult(
            isHardwareApplied = attempted > 0 && applied > 0,
            message = "Restored $applied of $attempted saved tuning choices"
        )
    }

    private val restoreMutex = Mutex()
    private val zramMutex = Mutex()

    @Volatile
    private var restoredThisProcess = false

    @Volatile
    private var lastRestoreResult: TuningApplyResult? = null

    /**
     * Ensure the saved tuning has been replayed once since this process started.
     *
     * The first caller performs the restore, everyone else waits for it, so the
     * tuning screen never renders kernel defaults that are about to be replaced.
     * Returns null when there is nothing saved.
     */
    suspend fun awaitSavedTuningRestored(): TuningApplyResult? {
        if (!hasSavedState()) return null
        return restoreMutex.withLock {
            if (restoredThisProcess) return@withLock lastRestoreResult
            val result = reapplySaved()
            if (result.isHardwareApplied) {
                restoredThisProcess = true
                lastRestoreResult = result
            }
            result
        }
    }

    /** Retry bookkeeping for the post-boot restore job. */
    fun restoreAttempts(): Int = prefs.getInt(KEY_RESTORE_ATTEMPTS, 0)

    fun noteRestoreAttempt() {
        runCatching { prefs.edit().putInt(KEY_RESTORE_ATTEMPTS, restoreAttempts() + 1).commit() }
    }

    fun clearRestoreAttempts() {
        runCatching { prefs.edit().remove(KEY_RESTORE_ATTEMPTS).commit() }
    }

    // ---- Gaming / performance ----

    suspend fun setGamingMode(level: GamingModeLevel): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putInt(KEY_GAMING_MODE, level.value) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied(level.title)
        if (!isSupported(TuningFeature.GAMING_MODE)) return@withContext unsupported(TuningFeature.GAMING_MODE)

        // The controller boots into its dynamic "auto" engine, which treats
        // level writes as hints: vote hysteresis can keep the old profile
        // (Gaming -> Extreme, or any step down) until real load happens to
        // change, so the setting looked like it never applied. "manual" pins
        // the engine to exactly what is written next, which is also what the
        // UI reads back.
        ShellUtils.fastWrite(
            KernelNodes.GAMING_MODE_PROC,
            "manual",
            verify = { dump -> dump.contains("(manual") }
        )

        val boost = boostForMode[level] ?: 0

        // `/proc/perfmgr/gaming_mode` answers with a multi-line status dump, so
        // its write is verified against the `gaming_mode: N` line rather than
        // the generic exact-match check.
        val written = listOf(
            ShellUtils.fastWrite(
                KernelNodes.GAMING_MODE_PROC,
                level.value.toString(),
                verify = { dump ->
                    gamingProcLine.find(dump)?.groupValues?.get(1)?.toIntOrNull() == level.value
                }
            ),
            ShellUtils.fastWrite(KernelNodes.GAMING_MODE_SYS, level.value.toString()),
            ShellUtils.fastWrite(KernelNodes.BOOST_FG, boost.toString())
        )

        if (boost > 0) {
            applyTouchBoost(true, level)
        } else {
            applyTouchBoost(false, level)
        }

        if (written.none { it }) {
            return@withContext TuningApplyResult(
                false,
                "Kernel rejected the write - none of ${KernelNodes.GAMING_MODE_PROC}, " +
                    "${KernelNodes.GAMING_MODE_SYS}, ${KernelNodes.BOOST_FG} are writable"
            )
        }

        // Say what the kernel actually runs: the driver derates Extreme to
        // Gaming while the battery is hot or nearly empty.
        val applied = readGamingRaw()
        if (applied != null && applied != level.value) {
            val appliedLevel = gamingLevelOf(applied)
            val detail =
                if (level == GamingModeLevel.EXTREME && appliedLevel == GamingModeLevel.GAMING) {
                    "derated to Gaming (battery health guard)"
                } else {
                    "kernel reports ${appliedLevel.title}"
                }
            TuningApplyResult(true, "${level.title} requested - $detail")
        } else {
            TuningApplyResult(true, "${level.title} - EAS boost $boost")
        }
    }

    private suspend fun applyTouchBoost(enable: Boolean, level: GamingModeLevel): TuningApplyResult {
        val boost = boostForMode[level] ?: 0
        val ok = ShellUtils.fastWrite(
            KernelNodes.USRTCH,
            if (enable) "enable 1" else "enable 0",
            verify = { dump -> KernelNodes.parseUsrtchField(dump, "enable") == (if (enable) 1 else 0) }
        ) && if (enable) {
            ShellUtils.fastWrite(
                KernelNodes.USRTCH,
                "eas_boost $boost",
                verify = { dump -> KernelNodes.parseUsrtchField(dump, "eas_boost") == boost }
            )
        } else true

        return TuningApplyResult(ok, if (ok) "Touch boost set" else "Touch boost rejected by FPSGO")
    }

    suspend fun setTouchBoost(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_TOUCH_BOOST, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Touch boost")
        if (!isSupported(TuningFeature.TOUCH_BOOST)) return@withContext unsupported(TuningFeature.TOUCH_BOOST)
        applyTouchBoost(enable, GamingModeLevel.GAMING)
    }

    suspend fun setFpsCap(enabled: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean("fps_cap", enabled) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("FPS cap")
        if (!isSupported(TuningFeature.FPS_CAP)) return@withContext unsupported(TuningFeature.FPS_CAP)

        applyKernelWrites(
            successLabel = if (enabled) "FPS cap released" else "FPS cap enforced at 60/90/120/144",
            KernelNodes.FPS_FORCE_DISABLE to if (enabled) "0" else "1"
        )
    }

    // ---- Battery guard & charging controls ----

    suspend fun setBypassCharging(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_BYPASS_CHARGING, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Bypass charging")
        if (!isSupported(TuningFeature.BYPASS_CHARGING)) return@withContext unsupported(TuningFeature.BYPASS_CHARGING)
        applyKernelWrites(
            successLabel = if (enable) "Direct-Power bypass mode active (charging disabled)" else "Normal charging enabled",
            KernelNodes.BATTERY_BYPASS_PROC to if (enable) "1" else "0",
            KernelNodes.BATTERY_BYPASS_SYS to if (enable) "1" else "0",
            KernelNodes.BATTERY_CHARGING_ENABLED to if (enable) "0" else "1"
        )
    }

    suspend fun setFastCharge(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_FAST_CHARGE, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Fast charge")
        if (!isSupported(TuningFeature.FAST_CHARGE)) return@withContext unsupported(TuningFeature.FAST_CHARGE)
        applyKernelWrites(
            successLabel = if (enable) "18W fast charge enabled" else "Fast charge disabled (slower, safer on PC USB)",
            KernelNodes.FAST_CHARGE_PROC to if (enable) "1" else "0"
        )
    }

    suspend fun setChargeLimit(percent: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        val clamped = percent.coerceIn(50, 100)
        persist { putInt(KEY_CHARGE_LIMIT, clamped) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Charge limit")
        if (!isSupported(TuningFeature.CHARGE_LIMIT)) return@withContext unsupported(TuningFeature.CHARGE_LIMIT)
        applyKernelWrites(
            successLabel = "Charging stop limit set to $clamped%",
            KernelNodes.BATTERY_LIMIT_PROC to clamped.toString(),
            KernelNodes.BATTERY_LIMIT_SYS to clamped.toString(),
            KernelNodes.BATTERY_CHARGE_CONTROL_LIMIT to clamped.toString()
        )
    }

    suspend fun setThermalGuard(enable: Boolean, tempLimit: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        val clampedTemp = tempLimit.coerceIn(30, 55)
        persist {
            putBoolean(KEY_THERMAL_GUARD, enable)
            putInt(KEY_TEMP_LIMIT, clampedTemp)
        }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Thermal guard")
        if (!isSupported(TuningFeature.THERMAL_GUARD)) return@withContext unsupported(TuningFeature.THERMAL_GUARD)
        applyKernelWrites(
            successLabel = if (enable) "Thermal guard active (cutoff at ${clampedTemp}°C)" else "Thermal guard disabled",
            KernelNodes.THERMAL_GUARD_SYS to if (enable) "1" else "0",
            KernelNodes.TEMP_LIMIT_SYS to clampedTemp.toString()
        )
    }

    // ---- xswapd: userspace daemon ----

    suspend fun setXswapdEnabled(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_XSWAPD_ENABLE, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("xswapd")
        if (!isSupported(TuningFeature.XSWAPD)) return@withContext unsupported(TuningFeature.XSWAPD)
        applyKernelWrites(
            successLabel = if (enable) "xswapd daemon enabled" else "xswapd daemon disabled",
            "/dev/memcg/memory.xswapd.enable" to if (enable) "1" else "0"
        )
    }

    suspend fun triggerXswapdReclaim(): TuningApplyResult = withContext(Dispatchers.IO) {
        if (!ShellUtils.isRootAvailable()) {
            System.gc()
            return@withContext TuningApplyResult(false, "Triggered app-level memory clean (root required for kernel xswapd reclaim)")
        }
        if (!isSupported(TuningFeature.XSWAPD)) return@withContext unsupported(TuningFeature.XSWAPD)
        applyKernelWrites(
            successLabel = "Triggered 100MB xswapd memory reclaim",
            "/dev/memcg/memory.xswapd.reclaim" to "104857600"
        )
    }

    // ---- Touch / display / haptics ----

    suspend fun setDoubleTapToWake(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_DT2W, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Double Tap to Wake")
        if (!isSupported(TuningFeature.DT2W)) return@withContext unsupported(TuningFeature.DT2W)
        applyKernelWrites(
            successLabel = if (enable) "Double Tap to Wake enabled" else "Double Tap to Wake disabled",
            KernelNodes.DT2W_PROC to if (enable) "1" else "0"
        )
    }

    suspend fun setVibratorStrength(percent: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        val clamped = percent.coerceIn(0, 100)
        persist { putInt(KEY_VIBRATOR_STRENGTH, clamped) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Vibrator strength")
        if (!isSupported(TuningFeature.VIBRATOR_STRENGTH)) return@withContext unsupported(TuningFeature.VIBRATOR_STRENGTH)
        val level = (clamped * 6 / 100).coerceIn(1, 6)
        applyKernelWrites(
            successLabel = "Vibrator strength set to $clamped% (level=$level)",
            KernelNodes.VIBRATOR_STRENGTH_PROC to level.toString(),
            KernelNodes.VIBRATOR_VMAX to level.toString()
        )
    }

    // ---- Storage ----

    suspend fun setDynamicFsync(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_DYNAMIC_FSYNC, enable) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Dynamic Fsync")
        if (!isSupported(TuningFeature.DYNAMIC_FSYNC)) return@withContext unsupported(TuningFeature.DYNAMIC_FSYNC)
        applyKernelWrites(
            successLabel = if (enable) "Dynamic Fsync active" else "Dynamic Fsync disabled",
            KernelNodes.DYNAMIC_FSYNC_PROC to if (enable) "1" else "0",
            KernelNodes.DYNAMIC_FSYNC_SYS to if (enable) "1" else "0"
        )
    }

    suspend fun setReadAheadKb(kb: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putInt(KEY_READ_AHEAD_KB, kb) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Readahead buffer")
        val queues = KernelNodes.storageDevices().map { "$it/queue" }.ifEmpty { blockQueues() }
        if (queues.isEmpty()) {
            return@withContext TuningApplyResult(false, "No writable block queue found under /sys/block")
        }
        applyKernelWrites(
            successLabel = "Readahead buffer set to ${kb}KB on ${queues.size} queue(s)",
            *queues.map { "$it/read_ahead_kb" to kb.toString() }.toTypedArray()
        )
    }

    /** Block queues that actually exist, discovered instead of guessed. */
    private suspend fun blockQueues(): List<String> =
        KernelNodes.blockDevices().map { "$it/queue" }

    private suspend fun notApplied(action: String): TuningApplyResult =
        TuningApplyResult(false, "$action saved (root required to apply to kernel directly)")

    // ---- Standard Linux controls: these work on every root solution ----

    /**
     * Switch the I/O scheduler on every physical storage queue.
     *
     * `queue/scheduler` expects the whole option list back with the new entry
     * bracketed, so a bare scheduler name is rejected. begonia uses blk-mq on
     * UFS and advertises `none`, `mq-deadline` and `kyber`, plus `bfq` once the
     * kernel is built with CONFIG_IOSCHED_BFQ. A queue that simply does not
     * offer the requested scheduler (for example an SD card using a different
     * elevator) is counted as skipped, not as a write failure.
     */
    suspend fun setIoScheduler(scheduler: String): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putString(KEY_IO_SCHEDULER, scheduler) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("I/O scheduler")
        if (!isSupported(TuningFeature.IO_SCHEDULER)) return@withContext unsupported(TuningFeature.IO_SCHEDULER)

        val queues = KernelNodes.storageDevices().map { "$it/queue" }.ifEmpty { blockQueues() }
        var applied = 0
        val failures = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (queue in queues) {
            val raw = ShellUtils.readNode("$queue/scheduler") ?: continue
            val (active, options) = KernelNodes.parseSelection(raw)
            if (scheduler !in options) {
                skipped += queue.removeSuffix("/queue").substringAfterLast('/')
                continue
            }
            if (active == scheduler) {
                applied++
                continue
            }
            val payload = KernelNodes.renderSelection(options, scheduler)
            if (payload == null) {
                failures += queue
                continue
            }
            val wrote = ShellUtils.fastWrite("$queue/scheduler", payload) { readBack ->
                KernelNodes.parseSelection(readBack).first == scheduler
            }
            if (wrote) applied++ else failures += queue
        }

        val skippedNote = if (skipped.isEmpty()) "" else
            " (not offered by ${skipped.distinct().joinToString(", ")})"
        when {
            applied > 0 && failures.isEmpty() ->
                TuningApplyResult(true, "I/O scheduler set to $scheduler$skippedNote")
            applied > 0 ->
                TuningApplyResult(true, "I/O scheduler set to $scheduler$skippedNote, ${failures.size} queue(s) rejected it")
            else -> TuningApplyResult(
                false,
                "Kernel rejected $scheduler - no storage queue advertises it$skippedNote"
            )
        }
    }

    /**
     * Set the cpufreq governor on every policy.
     *
     * begonia compiles schedutil, performance, powersave, ondemand and userspace.
     */
    suspend fun setCpuGovernor(governor: String): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putString(KEY_CPU_GOVERNOR, governor) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("CPU governor")
        if (!isSupported(TuningFeature.CPU_GOVERNOR)) return@withContext unsupported(TuningFeature.CPU_GOVERNOR)

        val policies = KernelNodes.cpufreqPolicies()
        if (policies.isEmpty()) return@withContext TuningApplyResult(false, "No cpufreq policy found")

        var applied = 0
        for (policy in policies) {
            if (ShellUtils.fastWrite("$policy/cpufreq/scaling_governor", governor)) applied++
        }

        if (applied == 0) {
            TuningApplyResult(false, "No policy accepted '$governor'")
        } else {
            TuningApplyResult(true, "Governor '$governor' on $applied/${policies.size} policies")
        }
    }

    // ---- zram: compressed swap, optional disk writeback ----

    /**
     * Resize zram, preserving the current backing/writeback choices.
     *
     * `disksize` cannot be written while zram is initialized or used as swap,
     * which is what the old direct write did. The asset script performs the
     * required swapoff -> reset -> configure -> mkswap -> swapon sequence.
     */
    suspend fun setZramSizeMb(sizeMb: Long): TuningApplyResult = withContext(Dispatchers.IO) {
        val clamped = sizeMb.coerceIn(64, 8192)
        persist { putLong(KEY_ZRAM_SIZE_MB, clamped) }
        applyZramConfiguration(
            sizeMb = clamped,
            backingEnabled = prefs.getBoolean(KEY_ZRAM_BACKING_ENABLED, false),
            backingSizeMb = prefs.getLong(KEY_ZRAM_BACKING_SIZE_MB, 0L).takeIf { it > 0 } ?: clamped,
            compAlgorithm = prefs.getString(KEY_ZRAM_COMP_ALGORITHM, null),
            writebackLimitMb = prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L),
            writebackLimitEnabled = prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false)
        )
    }

    /**
     * Enable or disable the zram disk-writeback backing device.
     *
     * The Linux 4.14 driver only accepts a block device in `backing_dev` and
     * requires it before `disksize` is set. We create a sparse file, attach it
     * to a free loop device, and let the asset script hand that loop device to
     * zram. Disabling removes the loop device and the file after swapping out.
     */
    suspend fun setZramBackingEnabled(enabled: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        val currentSize = currentZramSizeMb()
        val storedSize = prefs.getLong(KEY_ZRAM_SIZE_MB, 0L).takeIf { it > 0 }
        if (!enabled && currentSize == null && storedSize == null) {
            persist { putBoolean(KEY_ZRAM_BACKING_ENABLED, false) }
            return@withContext TuningApplyResult(true, "ZRAM backing is already disabled")
        }
        val size = currentSize ?: storedSize ?: 2048L
        val backingSize = prefs.getLong(KEY_ZRAM_BACKING_SIZE_MB, 0L).takeIf { it > 0 } ?: size
        persist {
            putBoolean(KEY_ZRAM_BACKING_ENABLED, enabled)
            putLong(KEY_ZRAM_BACKING_SIZE_MB, backingSize)
            putLong(KEY_ZRAM_SIZE_MB, size)
        }
        applyZramConfiguration(
            sizeMb = size,
            backingEnabled = enabled,
            backingSizeMb = backingSize,
            compAlgorithm = prefs.getString(KEY_ZRAM_COMP_ALGORITHM, null),
            writebackLimitMb = prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L),
            writebackLimitEnabled = prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false)
        )
    }

    /** Change the size of the sparse backing file used for zram writeback. */
    suspend fun setZramBackingSizeMb(sizeMb: Long): TuningApplyResult = withContext(Dispatchers.IO) {
        val clamped = sizeMb.coerceIn(64, 8192)
        persist { putLong(KEY_ZRAM_BACKING_SIZE_MB, clamped) }
        if (!prefs.getBoolean(KEY_ZRAM_BACKING_ENABLED, false)) {
            return@withContext TuningApplyResult(
                false,
                "Backing size saved as ${formatMb(clamped)}; enable backing to apply it"
            )
        }
        val size = currentZramSizeMb() ?: prefs.getLong(KEY_ZRAM_SIZE_MB, 0L).takeIf { it > 0 } ?: 2048L
        applyZramConfiguration(
            sizeMb = size,
            backingEnabled = true,
            backingSizeMb = clamped,
            compAlgorithm = prefs.getString(KEY_ZRAM_COMP_ALGORITHM, null),
            writebackLimitMb = prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L),
            writebackLimitEnabled = prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false)
        )
    }

    /** Change the compression algorithm. Requires a zram reset, so swap is rebuilt. */
    suspend fun setZramCompressor(algorithm: String): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putString(KEY_ZRAM_COMP_ALGORITHM, algorithm) }
        val size = currentZramSizeMb() ?: prefs.getLong(KEY_ZRAM_SIZE_MB, 0L).takeIf { it > 0 }
            ?: return@withContext TuningApplyResult(
                false,
                "Compressor saved as $algorithm; choose a ZRAM size to activate it"
            )
        applyZramConfiguration(
            sizeMb = size,
            backingEnabled = prefs.getBoolean(KEY_ZRAM_BACKING_ENABLED, false),
            backingSizeMb = prefs.getLong(KEY_ZRAM_BACKING_SIZE_MB, 0L).takeIf { it > 0 } ?: size,
            compAlgorithm = algorithm,
            writebackLimitMb = prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L),
            writebackLimitEnabled = prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false)
        )
    }

    /** Set the disk writeback budget, in megabytes (0 = no budget). */
    suspend fun setZramWritebackLimitMb(limitMb: Long): TuningApplyResult = withContext(Dispatchers.IO) {
        val clamped = limitMb.coerceIn(0, 32768)
        persist { putLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, clamped) }
        applyZramWritebackLimit(clamped, prefs.getBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, false))
    }

    /** Enable or disable enforcement of the zram writeback budget. */
    suspend fun setZramWritebackLimitEnabled(enabled: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putBoolean(KEY_ZRAM_WRITEBACK_LIMIT_ENABLED, enabled) }
        applyZramWritebackLimit(prefs.getLong(KEY_ZRAM_WRITEBACK_LIMIT_MB, 0L), enabled)
    }

    /**
     * Ask zram to write idle (or incompressible) pages to the backing device.
     *
     * For idle writeback the kernel requires the pages to be marked idle first;
     * this mirrors the sequence documented in Documentation/blockdev/zram.txt.
     */
    suspend fun triggerZramWriteback(mode: ZramWritebackMode): TuningApplyResult = withContext(Dispatchers.IO) {
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("ZRAM writeback")
        if (!isSupported(TuningFeature.ZRAM_WRITEBACK)) {
            return@withContext unsupported(TuningFeature.ZRAM_WRITEBACK)
        }
        val backing = ShellUtils.fastRead(KernelNodes.ZRAM_BACKING_DEV)?.trim().orEmpty()
        if (backing.isEmpty() || backing.equals("none", ignoreCase = true)) {
            return@withContext TuningApplyResult(false, "Enable ZRAM backing before requesting writeback")
        }

        val trigger = when (mode) {
            ZramWritebackMode.IDLE ->
                "printf '%s\\n' 'all' > ${ShellUtils.quote(KernelNodes.ZRAM_IDLE)} && " +
                    "printf '%s\\n' 'idle' > ${ShellUtils.quote(KernelNodes.ZRAM_WRITEBACK)}"
            ZramWritebackMode.HUGE ->
                "printf '%s\\n' 'huge' > ${ShellUtils.quote(KernelNodes.ZRAM_WRITEBACK)}"
        }
        val result = zramMutex.withLock {
            ShellUtils.runCommand("exec 2>&1\n$trigger && echo POX_WB_OK")
        }
        if (!result.isSuccess || !result.output.any { it.contains("POX_WB_OK") }) {
            val detail = result.output.lastOrNull { it.isNotBlank() }.orEmpty()
            return@withContext TuningApplyResult(false, "Writeback failed${if (detail.isEmpty()) "" else ": $detail"}")
        }

        val written = parseBdStat(ShellUtils.fastRead(KernelNodes.ZRAM_BD_STAT))?.third?.div(256) ?: 0L
        TuningApplyResult(
            true,
            "Requested writeback of ${mode.label}; ${formatMb(written)} now on the backing device"
        )
    }

    private suspend fun applyZramWritebackLimit(limitMb: Long, enabled: Boolean): TuningApplyResult =
        withContext(Dispatchers.IO) {
            if (!ShellUtils.isRootAvailable()) return@withContext notApplied("ZRAM writeback limit")
            if (!isSupported(TuningFeature.ZRAM_WRITEBACK)) {
                return@withContext unsupported(TuningFeature.ZRAM_WRITEBACK)
            }

            // writeback_limit is expressed in 4KB units.
            val units = limitMb * 256
            val limitOk = ShellUtils.fastWrite(
                KernelNodes.ZRAM_WRITEBACK_LIMIT,
                units.toString()
            ) { it.trim().toLongOrNull() == units }
            val enableOk = ShellUtils.fastWrite(
                KernelNodes.ZRAM_WRITEBACK_LIMIT_ENABLE,
                if (enabled) "1" else "0"
            )

            if (limitOk && enableOk) {
                TuningApplyResult(
                    true,
                    if (enabled) "Writeback budget limited to ${formatMb(limitMb)}"
                    else "Writeback budget disabled"
                )
            } else {
                TuningApplyResult(false, "Kernel rejected the writeback limit nodes")
            }
        }

    /**
     * Perform the only safe zram reconfiguration sequence on Linux 4.14.
     *
     * The driver refuses `disksize`/`comp_algorithm`/`backing_dev` changes while
     * the device is initialized, and refuses `reset` while it is open as swap.
     * Everything therefore happens in one root script, with a rollback to the
     * previous size if any step fails so the device is not left without swap.
     */
    private suspend fun applyZramConfiguration(
        sizeMb: Long,
        backingEnabled: Boolean,
        backingSizeMb: Long,
        compAlgorithm: String?,
        writebackLimitMb: Long,
        writebackLimitEnabled: Boolean
    ): TuningApplyResult = withContext(Dispatchers.IO) {
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("ZRAM")
        if (!isSupported(TuningFeature.ZRAM_SIZE)) return@withContext unsupported(TuningFeature.ZRAM_SIZE)
        if (backingEnabled && !isSupported(TuningFeature.ZRAM_BACKING)) {
            return@withContext unsupported(TuningFeature.ZRAM_BACKING)
        }
        if (!compAlgorithm.isNullOrBlank() && !isSupported(TuningFeature.ZRAM_COMPRESSOR)) {
            return@withContext unsupported(TuningFeature.ZRAM_COMPRESSOR)
        }

        val clampedSize = sizeMb.coerceIn(64, 8192)
        val clampedBacking = (backingSizeMb.takeIf { it > 0 } ?: clampedSize).coerceIn(64, 8192)
        val limit = writebackLimitMb.coerceIn(0, 32768)

        // Reapplying on every app launch must not swapoff/reset a zram device
        // that is already in the requested state; that would throw away the
        // current swap contents for no reason.
        if (zramConfigurationMatches(clampedSize, backingEnabled, clampedBacking, compAlgorithm, limit, writebackLimitEnabled)) {
            return@withContext TuningApplyResult(
                true,
                "ZRAM ${formatMb(clampedSize)} already active" +
                    if (backingEnabled) " with ${formatMb(clampedBacking)} backing" else ""
            )
        }

        val script = try {
            installZramScript()
        } catch (error: Exception) {
            return@withContext TuningApplyResult(false, "Could not install zram helper: ${error.message}")
        }
        val args = listOf(
            if (backingEnabled) "1" else "0",
            clampedSize.toString(),
            clampedBacking.toString(),
            compAlgorithm.orEmpty(),
            limit.toString(),
            if (writebackLimitEnabled) "1" else "0",
            KernelNodes.ZRAM_BACKING_FILE
        ).joinToString(" ") { ShellUtils.quote(it) }

        // Stage the helper outside the app sandbox as well: some root domains
        // can write /data/local/tmp but are denied access to /data/user/0.
        val remoteScript = "/data/local/tmp/pox-zram-config.sh"
        val staged = ShellUtils.runCommand(
            "cp ${ShellUtils.quote(script.absolutePath)} $remoteScript && " +
                "chmod 700 $remoteScript"
        )
        val scriptPath = if (staged.isSuccess) remoteScript else script.absolutePath

        val result = zramMutex.withLock {
            ShellUtils.runCommand(
                "exec 2>&1\nsh ${ShellUtils.quote(scriptPath)} $args"
            )
        }
        val output = result.output.joinToString("\n")
        if (!result.isSuccess || !output.contains("POX_ZRAM_OK")) {
            val detail = result.output.lastOrNull { it.contains("POX_ZRAM_ERROR") }
                ?: result.output.lastOrNull { it.isNotBlank() }
                ?: "unknown error"
            return@withContext TuningApplyResult(false, "ZRAM reconfiguration failed: $detail")
        }

        val backingLabel = if (backingEnabled) {
            val loop = output.lineSequence()
                .firstOrNull { it.contains("POX_ZRAM_OK") }
                ?.substringAfter("loop=", "")
                ?.trim()
                .orEmpty()
            ", backing ${formatMb(clampedBacking)}${if (loop.isNotEmpty()) " on $loop" else ""}"
        } else {
            ", no backing device"
        }
        TuningApplyResult(
            true,
            "ZRAM ${formatMb(clampedSize)} active$backingLabel" +
                if (compAlgorithm.isNullOrBlank()) "" else " (${compAlgorithm})"
        )
    }

    private suspend fun zramConfigurationMatches(
        sizeMb: Long,
        backingEnabled: Boolean,
        backingSizeMb: Long,
        compAlgorithm: String?,
        writebackLimitMb: Long,
        writebackLimitEnabled: Boolean
    ): Boolean {
        if (currentZramSizeMb() != sizeMb) return false
        val swapActive = ShellUtils.fastRead("/proc/swaps")
            ?.lineSequence()
            ?.any { it.startsWith("/dev/zram0 ") || it.startsWith("/dev/block/zram0 ") } == true
        if (!swapActive) return false

        if (!compAlgorithm.isNullOrBlank()) {
            val active = KernelNodes.parseSelection(
                ShellUtils.readNode(KernelNodes.ZRAM_COMP_ALGORITHM).orEmpty()
            ).first
            if (active != compAlgorithm) return false
        }

        val backing = ShellUtils.fastRead(KernelNodes.ZRAM_BACKING_DEV)?.trim().orEmpty()
        val hasBacking = backing.isNotEmpty() && !backing.equals("none", ignoreCase = true)
        if (hasBacking != backingEnabled) return false
        if (hasBacking && blockDeviceSizeMb(backing) != backingSizeMb) return false

        if (isSupported(TuningFeature.ZRAM_WRITEBACK)) {
            val limitUnits = ShellUtils.fastRead(KernelNodes.ZRAM_WRITEBACK_LIMIT)
                ?.trim()?.toLongOrNull() ?: 0L
            if (limitUnits != writebackLimitMb * 256) return false
            val limitEnabled = ShellUtils.fastRead(KernelNodes.ZRAM_WRITEBACK_LIMIT_ENABLE)?.trim() == "1"
            if (limitEnabled != writebackLimitEnabled) return false
        }
        return true
    }

    private fun installZramScript(): File {
        val context = PoxApplication.instance
        val script = File(context.filesDir, "zram_config.sh")
        context.assets.open("zram_config.sh").use { input ->
            script.outputStream().use { output -> input.copyTo(output) }
        }
        runCatching { script.setReadable(true, false) }
        return script
    }

    private suspend fun currentZramSizeMb(): Long? =
        ShellUtils.readNode(KernelNodes.ZRAM_DISKSIZE)
            ?.trim()?.toLongOrNull()?.div(1024 * 1024)?.takeIf { it > 0 }

    private suspend fun blockDeviceSizeMb(device: String): Long? {
        val name = device.substringAfterLast('/')
        if (name.isBlank()) return null
        val sectors = ShellUtils.readNode("/sys/block/$name/size")
            ?.trim()?.toLongOrNull() ?: return null
        return (sectors * 512L / (1024 * 1024)).takeIf { it > 0 }
    }

    /** `bd_stat` prints bd_count, bd_reads, bd_writes in 4KB units. */
    private fun parseBdStat(raw: String?): Triple<Long, Long, Long>? {
        val parts = raw?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024f) else "$mb MB"

    /** Cap the frame rate using MediaTek's syslimiter. */
    suspend fun setFpsCap(fps: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        persist { putInt("fps_cap_fps", fps) }
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("FPS cap")
        if (!isSupported(TuningFeature.FPS_CAP)) return@withContext unsupported(TuningFeature.FPS_CAP)

        if (fps == 0) {
            // 0 means uncapped: clear the limiter.
            return@withContext applyKernelWrites(
                successLabel = "FPS cap released",
                KernelNodes.FPS_FORCE_DISABLE to "1"
            )
        }

        val megahertz = fps * 1000
        val nodes = mapOf(
            60 to KernelNodes.FPS_60,
            90 to KernelNodes.FPS_90,
            120 to KernelNodes.FPS_120,
            144 to KernelNodes.FPS_144
        )
        val target = nodes[fps]
            ?: return@withContext TuningApplyResult(false, "This kernel exposes 60/90/120/144 only")

        val cleared = applyKernelWrites(
            successLabel = "FPS cap cleared on the other refresh rates",
            *nodes.filterValues { it != target }.values.map { it to "0" }.toTypedArray()
        )
        val applied = applyKernelWrites(
            successLabel = "FPS capped at $fps",
            KernelNodes.FPS_FORCE_DISABLE to "0",
            target to megahertz.toString()
        )

        if (applied.isHardwareApplied) applied
        else TuningApplyResult(cleared.isHardwareApplied, applied.message)
    }
}
