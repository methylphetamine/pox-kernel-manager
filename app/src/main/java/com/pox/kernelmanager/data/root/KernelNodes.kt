package com.pox.kernelmanager.data.root

import android.os.SystemClock
import com.pox.kernelmanager.data.model.TuningFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tuning node map for `POX-project/pox-begonia-kernel`, read from the source at
 * 8ca0974 (defconfigs `begonia_user_defconfig` and `begonia_apatch_defconfig`).
 *
 * None of the tuning paths this app originally shipped exist in this kernel.
 * A full-tree grep across `drivers/` and `include/` (41,650 files) returns zero
 * hits for `gaming_mode`, `battery_protection`, `xswapd`, `dyn_fsync`,
 * `true_tone`, `vibrator_strength`, `nomount` or `double_click`, so every write
 * to them was guaranteed to be rejected.
 *
 * What the kernel does export is MediaTek's perfmgr. It is built
 * unconditionally — `drivers/misc/mediatek/performance/Makefile` uses
 * `obj-y += perfmgr_main.o`, there is no `CONFIG_MTK_PERFMGR` symbol at all —
 * and `perfmgr_main.c:104-107` calls `init_tchbst()`, `init_boostctrl()` and
 * `syslimiter_init()` under `CONFIG_MTK_BASE_POWER`, which the defconfig sets to
 * `y`. So `/proc/perfmgr` and its children are real.
 *
 * The one exception is kernel touch boost: both defconfigs carry
 * `# CONFIG_MTK_PERFMGR_TOUCH_BOOST is not set`, and `tchbst_main.c` only calls
 * `init_ktch()` under that config. `/proc/perfmgr/tchbst/kernel/tb_enable` is
 * therefore **not** built. Touch boost must go through the FPSGO user node
 * instead, which is present because `CONFIG_MTK_FPSGO_V3=y`.
 */
object KernelNodes {

    // ---- perfmgr: boost control (eas_ctrl.c, clamp -100..100) ----

    /** Foreground EAS boost. Read/write, signed int, clamped by the kernel. */
    const val BOOST_FG = "/proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_fg_boost"

    /** Background EAS boost. */
    const val BOOST_BG = "/proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_bg_boost"

    /** Top-app EAS boost. */
    const val BOOST_TA = "/proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_ta_boost"

    /** Currently applied foreground boost (read-only). */
    const val CURRENT_BOOST_FG = "/proc/perfmgr/boost_ctrl/eas_ctrl/current_fg_boost"

    /** Pre-emptively clamp the big cluster. 0-100. */
    const val BOOT_BOOST = "/proc/perfmgr/boost_ctrl/eas_ctrl/boot_boost"

    /** Highest OPP to raise to on touch, 0-15 (utch.c validates this range). */
    const val UCLAMP_MIN_FG = "/proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_uclamp_min"

    // ---- perfmgr: FPSGO v3 user touch (utch.c, mode 0664) ----

    /**
     * Command interface: `"<cmd> <arg1> [arg2]"`.
     *
     * Valid commands are `enable`, `eas_boost`, `touch_opp`, `cluster_opp`,
     * `duration`, `active_time`, `time_to_last_touch` and `deboost_when_render`.
     * Reading it back returns a status dump, not the value, so writes must supply
     * their own verification predicate.
     */
    const val USRTCH = "/proc/perfmgr/tchbst/user/usrtch"

    /** Human-readable dump of the above; read-only. */
    const val USRTCH_DEBUG = "/proc/perfmgr/tchbst/user/usrdebug"

    // ---- perfmgr: FPS cap (syslimiter.c, MHz) ----

    const val FPS_60 = "/proc/perfmgr/syslimiter/syslimiter_fps_60"
    const val FPS_90 = "/proc/perfmgr/syslimiter/syslimiter_fps_90"
    const val FPS_120 = "/proc/perfmgr/syslimiter/syslimiter_fps_120"
    const val FPS_144 = "/proc/perfmgr/syslimiter/syslimiter_fps_144"

    /** Set non-zero to bypass the limiter entirely. */
    const val FPS_FORCE_DISABLE = "/proc/perfmgr/syslimiter/syslimiter_force_disable"

    /** Percent of frames allowed to exceed the limit. */
    const val FPS_TOLERANCE = "/proc/perfmgr/syslimiter/syslimiter_tolerance_percent"

    // ---- perfmgr: ioctl surfaces (perf_ioctl.c, mode 0664) ----

    const val PERF_IOCTL = "/proc/perfmgr/perf_ioctl"
    const val EARA_IOCTL = "/proc/perfmgr/eara_ioctl"

    // ---- Standard Linux nodes that do exist ----

    /**
     * Stock zram statistics.
     *
     * `drivers/block/zram/zram_drv.c` exposes `mm_stat`, not the vendor
     * `mem_used_total` / `orig_data_size` files this app was reading. Fields are
     * space separated, in order: orig_data_size, compr_data_size,
     * mem_used_total, mem_limit, max_used_pages, same_pages, pages_compacted,
     * huge_pages — all in bytes.
     */
    const val ZRAM_MM_STAT = "/sys/block/zram0/mm_stat"

    const val ZRAM_BASE = "/sys/block/zram0"
    const val ZRAM_DEVICE = "/dev/zram0"

    const val ZRAM_DISKSIZE = "$ZRAM_BASE/disksize"
    const val ZRAM_COMP_ALGORITHM = "$ZRAM_BASE/comp_algorithm"
    const val ZRAM_INITSTATE = "$ZRAM_BASE/initstate"
    const val ZRAM_RESET = "$ZRAM_BASE/reset"

    /**
     * zram writeback interface (`CONFIG_ZRAM_WRITEBACK`).
     *
     * This kernel is Linux 4.14, whose zram driver accepts a *block device* in
     * [ZRAM_BACKING_DEV], not an arbitrary file. The manager therefore creates a
     * sparse file, attaches it to a loop device, and hands that loop device to
     * the kernel.
     */
    const val ZRAM_BACKING_DEV = "$ZRAM_BASE/backing_dev"
    const val ZRAM_WRITEBACK = "$ZRAM_BASE/writeback"
    const val ZRAM_IDLE = "$ZRAM_BASE/idle"
    const val ZRAM_WRITEBACK_LIMIT = "$ZRAM_BASE/writeback_limit"
    const val ZRAM_WRITEBACK_LIMIT_ENABLE = "$ZRAM_BASE/writeback_limit_enable"
    const val ZRAM_BD_STAT = "$ZRAM_BASE/bd_stat"

    /**
     * Sparse file backing the zram writeback loop device.
     *
     * `/data/local/tmp` is available on every Android release and survives app
     * updates. The file is only ever opened by the kernel through the loop
     * device, so it does not need to live in the app sandbox.
     */
    const val ZRAM_BACKING_FILE = "/data/local/tmp/pox-zram-backing.img"

    /** Generic block queue attributes; the queue name must be discovered. */
    const val SYS_BLOCK = "/sys/block"

    /** Standard cpufreq attributes; the cpu index must be discovered. */
    const val CPUFREQ = "/sys/devices/system/cpu"

    // ---- Runtime discovery helpers ----

    /**
     * Block queues that exist, e.g. `["/sys/block/sda", "/sys/block/zram0"]`.
     *
     * begonia is UFS, so the queue is `sda` — but the name is read back rather
     * than assumed, because a different storage layout would break every
     * hardcoded path the app used to carry.
     */
    suspend fun blockDevices(): List<String> {
        val result = ShellUtils.runCommand("ls /sys/block 2>/dev/null")
        if (!result.isSuccess) return emptyList()
        return result.output.map { it.trim() }.filter { it.isNotEmpty() }.map { "/sys/block/$it" }
    }

    /**
     * Queues that represent real storage (UFS/eMMC/SD), not virtual devices.
     *
     * Writing an I/O scheduler to `zram0` or `loop0` is pointless: those queues
     * only ever advertise `none`, and including them in the option list made the
     * UI show a scheduler that was not actually selectable on the UFS queue.
     */
    suspend fun storageDevices(): List<String> {
        val result = ShellUtils.runCommand("ls /sys/block 2>/dev/null")
        if (!result.isSuccess) return emptyList()
        return result.output
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { name ->
                name.startsWith("zram") || name.startsWith("loop") ||
                    name.startsWith("ram") || name.startsWith("dm-")
            }
            .map { "/sys/block/$it" }
    }

    /** CPUs that expose a cpufreq policy, e.g. `["cpu0", "cpu6", ...]`. */
    suspend fun cpufreqPolicies(): List<String> {
        val result = ShellUtils.runCommand(
            "ls /sys/devices/system/cpu/cpu[0-9]*/cpufreq 2>/dev/null"
        )
        if (!result.isSuccess) return emptyList()
        return result.output
            .map { it.trim() }
            .filter { it.endsWith("/cpufreq") }
            .map { it.removeSuffix("/cpufreq") }
    }

    /**
     * Parse a sysfs scheduler or governor list.
     *
     * `queue/scheduler` reads `[deadline] kyber none` and `scaling_governor` reads
     * `schedutil`, so both go through the bracket-aware parser.
     */
    fun parseSelection(raw: String): Pair<String, List<String>> {
        val options = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val active = options.firstOrNull { it.startsWith("[") && it.endsWith("]") }
            ?.removePrefix("[")?.removeSuffix("]")
        return (active ?: "") to options.map { it.trim('[', ']') }.distinct()
    }

    /**
     * Every node this build probes, in the order it is checked.
     *
     * Surfaced through the diagnostics screen so a device report shows what is
     * really there rather than an inferred support matrix.
     */
    fun probedNodes(): List<String> = listOf(
        BOOST_FG,
        USRTCH,
        USRTCH_DEBUG,
        FPS_144,
        FPS_FORCE_DISABLE,
        PERF_IOCTL,
        "/proc/perfmgr/tchbst/kernel/tb_enable",
        "/sys/kernel/dyn_fsync/Dyn_fsync_active",
        "/dev/memcg/memory.xswapd.enable",
        ZRAM_DISKSIZE,
        ZRAM_MM_STAT,
        ZRAM_BACKING_DEV,
        ZRAM_WRITEBACK,
        ZRAM_IDLE,
        ZRAM_WRITEBACK_LIMIT,
        ZRAM_WRITEBACK_LIMIT_ENABLE,
        ZRAM_BD_STAT,
        ZRAM_COMP_ALGORITHM
    )

    /**
     * Render a sysfs selection list with [target] bracketed.
     *
     * The kernel expects the whole list back with the new entry marked, which is
     * why writing a bare scheduler name fails: it would leave the list malformed.
     */
    fun renderSelection(options: List<String>, target: String): String? {
        if (options.isEmpty() || options.none { it == target }) return null
        return options.joinToString(" ") { if (it == target) "[$it]" else it }
    }

    private const val PROBE_TTL_MS = 30_000L

    private val probeMutex = Mutex()

    private class Cache(val support: Map<TuningFeature, Boolean>, val createdAt: Long)

    @Volatile
    private var cached: Cache? = null

    /**
     * Which tuning features this kernel actually implements.
     *
     * Probed rather than assumed, because POX ships several editions and a
     * defconfig can change what gets built.
     */
    suspend fun support(): Map<TuningFeature, Boolean> = withContext(Dispatchers.IO) {
        probeMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - it.createdAt < PROBE_TTL_MS) return@withLock it.support }
            val fresh = probe()
            cached = Cache(fresh, now)
            fresh
        }
    }

    private suspend fun probe(): Map<TuningFeature, Boolean> {
        // Root is a precondition for every probe below. Without it libsu silently
        // falls back to a plain non-root `sh`, every `test -w` fails, and the UI
        // blames the kernel for a problem that is really "this app is not rooted".
        // Check once up front so that case is reported honestly and cheaply.
        if (!ShellUtils.isRootAvailable()) {
            return TuningFeature.entries.associateWith { false }
        }

        val writable: suspend (String) -> Boolean = { ShellUtils.nodeExists(it) }

        // Kernel touch boost is compiled out on begonia, so only accept tb_enable
        // if a kernel variant happens to build it. usrtch is the FPSGO path that
        // begonia actually has.
        val touchBoost = writable(USRTCH) || writable("/proc/perfmgr/tchbst/kernel/tb_enable")
        val perfBoost = writable(BOOST_FG)
        val fpsCap = writable(FPS_144)

        // dyn_fsync is not a kernel feature; it ships as a Magisk module that
        // creates this node. Absence simply means the module is not installed.
        val dynFsync = writable("/sys/kernel/dyn_fsync/Dyn_fsync_active")

        // xswapd is a MIUI/HyperOS userspace daemon exposing /dev/memcg, not a
        // kernel interface. It exists only on those ROMs.
        val xswapd = writable("/dev/memcg/memory.xswapd.enable")

        // read_ahead_kb, queue/scheduler and scaling_governor exist on every
        // stock Linux / MTK build; the queue and cpu names are discovered.
        var blockQueue: String? = null
        for (queue in storageDevices()) {
            if (writable("$queue/queue/read_ahead_kb")) {
                blockQueue = queue
                break
            }
        }

        val zramSize = writable(ZRAM_DISKSIZE)
        // The 4.14 driver needs a block device, so the manager needs a loop
        // helper as well as the sysfs nodes.
        val loopTool = ShellUtils.runCommand(
            "command -v losetup >/dev/null 2>&1 || command -v busybox >/dev/null 2>&1"
        ).isSuccess
        val zramBacking = loopTool &&
            writable(ZRAM_BACKING_DEV) && writable(ZRAM_WRITEBACK)
        val zramWriteback = zramBacking && writable(ZRAM_IDLE)
        val zramCompressor = writable(ZRAM_COMP_ALGORITHM)

        var cpuPolicy: String? = null
        for (policy in cpufreqPolicies()) {
            if (writable("$policy/cpufreq/scaling_governor")) {
                cpuPolicy = policy
                break
            }
        }

            return mapOf(
                // Gaming mode is emulated from perfmgr: boost + FPSGO touch boost.
                TuningFeature.GAMING_MODE to (perfBoost && touchBoost),
                TuningFeature.TOUCH_BOOST to touchBoost,
                TuningFeature.FPS_CAP to fpsCap,
                TuningFeature.DYNAMIC_FSYNC to dynFsync,
                TuningFeature.XSWAPD to xswapd,

                // No node exists for these in pox-begonia-kernel.
                TuningFeature.BYPASS_CHARGING to false,
                TuningFeature.CHARGE_LIMIT to false,
                TuningFeature.THERMAL_GUARD to false,
                TuningFeature.DT2W to false,
                TuningFeature.TRUE_TONE to false,
                TuningFeature.VIBRATOR_STRENGTH to false,

            TuningFeature.READ_AHEAD to (blockQueue != null),
            TuningFeature.IO_SCHEDULER to (blockQueue != null),
            TuningFeature.CPU_GOVERNOR to (cpuPolicy != null),
            TuningFeature.ZRAM_SIZE to zramSize,
            TuningFeature.ZRAM_BACKING to zramBacking,
            TuningFeature.ZRAM_WRITEBACK to zramWriteback,
            TuningFeature.ZRAM_COMPRESSOR to zramCompressor
        )
    }

    fun invalidate() {
        cached = null
    }

    /**
     * Parse an `usrtch` status dump for the value of a named field.
     *
     * `utch.c:device_show()` prints lines like `eas_boost:\t37`, so the field can
     * be confirmed after a command write even though the node does not echo the
     * value back.
     */
    fun parseUsrtchField(dump: String, field: String): Int? {
        val match = Regex("(?m)^\\s*${Regex.escape(field)}:\\s*(-?\\d+)").find(dump)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
