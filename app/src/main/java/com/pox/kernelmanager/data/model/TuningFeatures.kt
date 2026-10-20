package com.pox.kernelmanager.data.model

/**
 * A tuning control the UI can expose.
 *
 * Existence is decided at runtime by probing the kernel, because POX ships
 * several editions and the available nodes differ between them.
 */
enum class TuningFeature(val label: String, val unsupportedReason: String) {
    GAMING_MODE("Gaming mode", "Grant root - /proc/perfmgr is not writable"),
    TOUCH_BOOST("Touch boost", "Grant root - /proc/perfmgr/tchbst/user/usrtch is not writable"),
    FPS_CAP("FPS cap", "Grant root - /proc/perfmgr/syslimiter is not writable"),

    // No such node anywhere in pox-begonia-kernel.
    BYPASS_CHARGING("Bypass charging", "pox-begonia-kernel exposes no charging-control interface"),
    CHARGE_LIMIT("Charge limit", "pox-begonia-kernel exposes no charging-control interface"),
    THERMAL_GUARD("Thermal guard", "pox-begonia-kernel exposes no charging-control interface"),
    DT2W("Double tap to wake", "Handled by the touch HAL, not by a writable kernel node"),
    TRUE_TONE("True Tone", "Display calibration, not a writable kernel node"),
    VIBRATOR_STRENGTH("Vibrator strength", "Handled by the vibrator HAL, not a writable kernel node"),

    // Present only on some ROMs or with a module installed.
    XSWAPD("xswapd", "Needs /dev/memcg - MIUI/HyperOS ROMs only"),
    DYNAMIC_FSYNC("Dynamic fsync", "Install the dyn_fsync Magisk module to create this node"),

    // Standard Linux interfaces, available on every root solution.
    READ_AHEAD("Read-ahead", "No writable block queue found under /sys/block"),
    IO_SCHEDULER("I/O scheduler", "No writable queue scheduler found under /sys/block"),
    CPU_GOVERNOR("CPU governor", "No writable cpufreq governor found"),
    ZRAM_SIZE("ZRAM size", "/sys/block/zram0/disksize is not writable"),
    ZRAM_BACKING(
        "ZRAM backing store",
        "Kernel was built without CONFIG_ZRAM_WRITEBACK, or /sys/block/zram0/backing_dev is missing"
    ),
    ZRAM_WRITEBACK(
        "ZRAM writeback",
        "Kernel does not expose /sys/block/zram0/{idle,writeback}"
    ),
    ZRAM_COMPRESSOR(
        "ZRAM compressor",
        "/sys/block/zram0/comp_algorithm is not writable"
    )
}

/** Kernel writeback modes exposed by the Linux 4.14 zram driver. */
enum class ZramWritebackMode(val kernelValue: String, val label: String) {
    IDLE("idle", "idle pages"),
    HUGE("huge", "incompressible pages")
}

enum class GamingModeLevel(val value: Int, val title: String, val description: String) {
    BALANCED(0, "Balanced", "Standard governor with efficient energy scaling"),
    GAMING(1, "Gaming Mode", "EAS boost 50 + FPSGO touch boost"),
    EXTREME(2, "Extreme", "EAS boost 100 + FPSGO touch boost at max OPP")
}

data class TuningFeatures(
    // Gaming & Performance
    val gamingMode: GamingModeLevel = GamingModeLevel.BALANCED,
    val touchBoost240Hz: Boolean = false,

    // Smart Battery Guard & Bypass (no node in pox-begonia-kernel)
    val bypassCharging: Boolean = false,
    val chargeLimitPercent: Int = 100,
    val thermalGuardEnabled: Boolean = false,
    val tempLimitC: Int = 48,

    // MIUI / HyperOS xswapd (userspace daemon, not present on stock)
    val xswapdEnabled: Boolean = false,
    val xswapdQuotaMb: Long = 0,

    // Hardware Touch & Display (no node in pox-begonia-kernel)
    val doubleTapToWake: Boolean = false,
    val trueToneDisplay: Boolean = false,
    val vibratorStrengthPercent: Int = 85,

    // Storage & I/O
    val dynamicFsync: Boolean = false,
    val readAheadKb: Int = 256,

    // begonia uses blk-mq on UFS; the queue advertises none, mq-deadline,
    // kyber and (with CONFIG_IOSCHED_BFQ) bfq. The list is read back from the
    // physical queue rather than hardcoded, because editions can differ.
    val ioScheduler: String = "",
    val availableSchedulers: List<String> = emptyList(),

    // begonia compiles schedutil, performance, powersave, ondemand and userspace.
    val cpuGovernor: String = "schedutil",
    val availableGovernors: List<String> = emptyList(),

    val zramSizeMb: Long = 0,
    val zramCompAlgorithm: String = "",
    val availableZramAlgorithms: List<String> = emptyList(),

    // zram writeback (`CONFIG_ZRAM_WRITEBACK`). The kernel only accepts a block
    // device, so [zramBackingDevice] is normally a loop device created by the
    // manager on top of [com.pox.kernelmanager.data.root.KernelNodes.ZRAM_BACKING_FILE].
    val zramBackingEnabled: Boolean = false,
    val zramBackingSizeMb: Long = 0,
    val zramBackingDevice: String = "",
    val zramWritebackLimitMb: Long = 0,
    val zramWritebackLimitEnabled: Boolean = false,
    /** Data written to the backing device, from `bd_stat`'s third column. */
    val zramWritebackMb: Long = 0,

    /** Physical queue whose scheduler is shown in the picker, e.g. `sda`. */
    val schedulerQueue: String = "",

    /**
     * Whether this app holds an active root shell.
     *
     * Kept separate from [unsupported] because libsu silently falls back to a
     * plain `sh` when `su` is denied. Without this, "Magisk has not granted root
     * to the app" was indistinguishable from "this kernel has no such node", and
     * every control was greyed out with the wrong explanation.
     */
    val rootGranted: Boolean = false,

    /** Root provider detected, for the status line. */
    val rootProviderLabel: String = "",

    /** Features this kernel does not implement, so the UI can disable them. */
    val unsupported: Set<TuningFeature> = emptySet()
) {
    /**
     * Whether a control can be used.
     *
     * Without root nothing can be written regardless of what the kernel has, so
     * the root check comes first.
     */
    fun isSupported(feature: TuningFeature): Boolean =
        rootGranted && feature !in unsupported

    /** Why a control is disabled, for display next to it. */
    fun reasonFor(feature: TuningFeature): String? = when {
        !rootGranted ->
            "Pox Kernel Manager has not been granted root. Open Magisk > Superuser, " +
                "grant this app, then reopen it. If Zygisk is enabled, make sure " +
                "the app is not on the enforce-denylist."
        feature in unsupported -> feature.unsupportedReason
        else -> null
    }
}