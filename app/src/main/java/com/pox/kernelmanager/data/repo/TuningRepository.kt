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
            rejected.isEmpty() -> TuningApplyResult(true, successLabel)
            accepted.isNotEmpty() -> TuningApplyResult(
                true,
                "$successLabel (this kernel does not expose ${rejected.joinToString()})"
            )
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

        // Gaming mode is derived from the live EAS boost the kernel reports,
        // snapping to whichever level is closest.
        val savedGaming = prefs.getInt("gaming_mode", 0)
        val gamingRaw = if (isRoot) {
            val boost = ShellUtils.readNode(KernelNodes.BOOST_FG)?.trim()?.toIntOrNull()
            if (boost != null) {
                boostForMode.entries.minByOrNull { kotlin.math.abs(it.value - boost) }?.key?.value
                    ?: savedGaming
            } else savedGaming
        } else savedGaming

        val gamingLevel = when (gamingRaw) {
            1 -> GamingModeLevel.GAMING
            2 -> GamingModeLevel.EXTREME
            else -> GamingModeLevel.BALANCED
        }

        val touchBoost = if (isRoot) {
            KernelNodes.parseUsrtchField(
                ShellUtils.readNode(KernelNodes.USRTCH_DEBUG).orEmpty(),
                "enable"
            )?.let { it == 0 } ?: false
        } else prefs.getBoolean("touch_boost", false)

        // Battery guard: no kernel interface in this tree.
        val bypass = prefs.getBoolean("bypass_charging", false)
        val chargeLimit = prefs.getInt("charge_limit", 100)
        val thermalGuard = prefs.getBoolean("thermal_guard", false)
        val tempLimit = prefs.getInt("temp_limit", 48)

        // xswapd is a MIUI/HyperOS userspace daemon.
        val xswapdEnable = if (isRoot && isSupported(TuningFeature.XSWAPD)) {
            ShellUtils.fastRead("/dev/memcg/memory.xswapd.enable") != "0"
        } else prefs.getBoolean("xswapd_enable", false)

        val savedXswapdQuota = prefs.getLong("xswapd_quota", 1024L)
        val xswapdQuota = if (isRoot && isSupported(TuningFeature.XSWAPD)) {
            (ShellUtils.fastRead("/dev/memcg/memory.xswapd.quota")?.toLongOrNull()
                ?: (savedXswapdQuota * 1024 * 1024)) / (1024 * 1024)
        } else savedXswapdQuota

        // Touch, display and haptics have no kernel interface here.
        val dt2w = prefs.getBoolean("dt2w", false)
        val trueTone = prefs.getBoolean("true_tone", false)
        val vibrator = prefs.getInt("vibrator_strength", 85)

        // Dynamic fsync is a Magisk module, not a kernel feature.
        val fsync = if (isRoot && isSupported(TuningFeature.DYNAMIC_FSYNC)) {
            ShellUtils.fastRead("/sys/kernel/dyn_fsync/Dyn_fsync_active") == "1"
        } else prefs.getBoolean("dynamic_fsync", false)

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
        val (activeGovernor, governorOptions) = KernelNodes.parseSelection(governorRaw)

        val zramSize = ShellUtils.readNode("/sys/block/zram0/disksize")?.trim()?.toLongOrNull()?.div(1024 * 1024) ?: 0L

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
            chargeLimitPercent = chargeLimit,
            thermalGuardEnabled = thermalGuard,
            tempLimitC = tempLimit,
            xswapdEnabled = xswapdEnable,
            xswapdQuotaMb = xswapdQuota,
            doubleTapToWake = dt2w,
            trueToneDisplay = trueTone,
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
            unsupported = support.filterValues { !it }.keys
        )
    }

    private val zramMutex = Mutex()

    /** Persist a preference change synchronously. */
    private fun persist(block: SharedPreferences.Editor.() -> Unit) {
        runCatching {
            val editor = prefs.edit()
            editor.block()
            editor.commit()
        }
    }

    // ---- Gaming / performance ----

    suspend fun setGamingMode(level: GamingModeLevel): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putInt("gaming_mode", level.value).apply()
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied(level.title)
        if (!isSupported(TuningFeature.GAMING_MODE)) return@withContext unsupported(TuningFeature.GAMING_MODE)

        val boost = boostForMode[level] ?: 0

        // Boost the foreground EAS bucket; this is the real performance lever.
        val boosted = applyKernelWrites(
            successLabel = "${level.title} - EAS boost $boost",
            KernelNodes.BOOST_FG to boost.toString()
        )

        // Touch boost goes through FPSGO's command node, which echoes back a status
        // dump rather than the value, so it needs its own verification.
        if (boost > 0) {
            applyTouchBoost(true, level)
        } else {
            applyTouchBoost(false, level)
        }

        boosted
    }

    private suspend fun applyTouchBoost(enable: Boolean, level: GamingModeLevel): TuningApplyResult {
        val boost = boostForMode[level] ?: 0
        val ok = ShellUtils.fastWrite(
            KernelNodes.USRTCH,
            if (enable) "enable 1" else "enable 0",
            verify = { dump -> KernelNodes.parseUsrtchField(dump, "enable") == (if (enable) 0 else 1) }
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
        prefs.edit().putBoolean("touch_boost", enable).apply()
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Touch boost")
        if (!isSupported(TuningFeature.TOUCH_BOOST)) return@withContext unsupported(TuningFeature.TOUCH_BOOST)
        applyTouchBoost(enable, GamingModeLevel.GAMING)
    }

    suspend fun setFpsCap(enabled: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("fps_cap", enabled).apply()
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("FPS cap")
        if (!isSupported(TuningFeature.FPS_CAP)) return@withContext unsupported(TuningFeature.FPS_CAP)

        // Force-disable is the on/off switch; the per-refresh-rate nodes are the
        // limits themselves, in MHz.
        applyKernelWrites(
            successLabel = if (enabled) "FPS cap released" else "FPS cap enforced at 60/90/120/144",
            KernelNodes.FPS_FORCE_DISABLE to if (enabled) "0" else "1"
        )
    }

    // ---- Battery guard: no kernel interface ----

    suspend fun setBypassCharging(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("bypass_charging", enable).apply()
        unsupported(TuningFeature.BYPASS_CHARGING)
    }

    suspend fun setChargeLimit(percent: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putInt("charge_limit", percent.coerceIn(60, 100)).apply()
        unsupported(TuningFeature.CHARGE_LIMIT)
    }

    suspend fun setThermalGuard(enable: Boolean, tempLimit: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("thermal_guard", enable).putInt("temp_limit", tempLimit).apply()
        unsupported(TuningFeature.THERMAL_GUARD)
    }

    // ---- xswapd: userspace daemon ----

    suspend fun setXswapdEnabled(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("xswapd_enable", enable).apply()
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

    // ---- Touch / display / haptics: no kernel interface ----

    suspend fun setDoubleTapToWake(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("dt2w", enable).apply()
        unsupported(TuningFeature.DT2W)
    }

    suspend fun setTrueTone(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("true_tone", enable).apply()
        unsupported(TuningFeature.TRUE_TONE)
    }

    suspend fun setVibratorStrength(percent: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putInt("vibrator_strength", percent.coerceIn(0, 100)).apply()
        unsupported(TuningFeature.VIBRATOR_STRENGTH)
    }

    // ---- Storage ----

    suspend fun setDynamicFsync(enable: Boolean): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("dynamic_fsync", enable).apply()
        if (!ShellUtils.isRootAvailable()) return@withContext notApplied("Dynamic Fsync")
        if (!isSupported(TuningFeature.DYNAMIC_FSYNC)) {
            return@withContext TuningApplyResult(
                false,
                "Dynamic Fsync is a Magisk module, not a kernel feature - /sys/kernel/dyn_fsync is absent"
            )
        }
        applyKernelWrites(
            successLabel = if (enable) "Dynamic Fsync Active" else "Dynamic Fsync Off",
            "/sys/kernel/dyn_fsync/Dyn_fsync_active" to if (enable) "1" else "0"
        )
    }

    suspend fun setReadAheadKb(kb: Int): TuningApplyResult = withContext(Dispatchers.IO) {
        prefs.edit().putInt("read_ahead_kb", kb).apply()
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