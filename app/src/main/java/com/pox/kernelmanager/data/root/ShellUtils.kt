package com.pox.kernelmanager.data.root

import android.os.SystemClock
import com.pox.kernelmanager.data.model.RootProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ShellResult(
    val isSuccess: Boolean,
    val output: List<String>,
    val exitCode: Int
)

/**
 * Everything the root layer learns from one batched shell probe.
 *
 * [provider] is which framework is installed and [granted] is whether *this* app
 * currently holds an active root shell. They are deliberately independent: a
 * device can be rooted while this app is denied, and reporting those as one
 * state is what made the old detector tell the user it was rooted when it was not.
 */
data class RootEnvironment(
    val provider: RootProvider,
    val version: String,
    val granted: Boolean,
    val zygisk: Boolean,
    val moduleCount: Int,
    val metamodule: String?,
    val probes: Map<String, String>
) {
    val isRooted: Boolean get() = granted
}

object ShellUtils {

    private const val PROBE_PREFIX = "pox."

    /**
     * One shell round-trip for the whole environment. Written flat and with each
     * test on its own line: `A || B && C` parses as `(A || B) && C` in POSIX sh,
     * which silently discarded half the output of the previous heuristic.
     *
     * The script always ends in `exit 0` so libsu reports success even when the
     * final `[ -e ... ]` test is false.
     */
    private val PROBE_SCRIPT: String = buildString {
        appendLine("echo \"${PROBE_PREFIX}uid=\$(id -u 2>/dev/null)\"")

        // APatch proper owns /data/adb/ap. /dev/kp alone is KernelPatch, not APatch.
        appendLine("[ -d /data/adb/ap ] && echo \"${PROBE_PREFIX}ap_dir=1\"")
        appendLine("[ -x /data/adb/ap/bin/apd ] && echo \"${PROBE_PREFIX}apd=1\"")
        appendLine("[ -x /data/adb/ap/bin/apstat ] && echo \"${PROBE_PREFIX}apstat=1\"")
        appendLine("[ -d /data/adb/ap/modules ] && echo \"${PROBE_PREFIX}ap_modules=1\"")
        // KernelPatch supercall device: present under APatch too, so it is a fallback only.
        appendLine("[ -e /dev/kp ] && echo \"${PROBE_PREFIX}kp=1\"")

        // KernelSU lineage. /data/adb/ksu/bin/ksud is the canonical daemon link.
        appendLine("[ -x /data/adb/ksud ] && echo \"${PROBE_PREFIX}ksud=1\"")
        appendLine("[ -x /data/adb/ksu/bin/ksud ] && echo \"${PROBE_PREFIX}ksud_bin=1\"")
        appendLine("[ -d /data/adb/ksu ] && echo \"${PROBE_PREFIX}ksu_dir=1\"")
        appendLine("[ -e /data/adb/ksu/.ksurc ] && echo \"${PROBE_PREFIX}ksurc=1\"")
        appendLine("[ -e /sys/module/kernelsu ] && echo \"${PROBE_PREFIX}ksu_lkm=1\"")
        // BakaSU-only artifacts (see userspace/ksud/src/defs.rs upstream).
        appendLine("[ -e /data/adb/ksu/lib/libadbroot.so ] && echo \"${PROBE_PREFIX}adbroot=1\"")
        appendLine("[ -e /data/adb/ksu/.dynamic_manager ] && echo \"${PROBE_PREFIX}dynamic_mgr=1\"")
        appendLine("[ -d /data/adb/ksu/module_configs ] && echo \"${PROBE_PREFIX}module_configs=1\"")
        appendLine("[ -e /data/adb/ksu/sulogd.lock ] && echo \"${PROBE_PREFIX}sulogd_lock=1\"")

        // Modules and metamodule (MKSU ships as a metamodule rather than a manager).
        appendLine("echo \"${PROBE_PREFIX}module_count=\$(ls -1 /data/adb/modules 2>/dev/null | wc -l | tr -d ' ')\"")
        appendLine("echo \"${PROBE_PREFIX}metamodule=\$(ls -1 /data/adb/metamodule 2>/dev/null | head -n 1)\"")

        // Magisk.
        appendLine("[ -e /dev/.magisk ] && echo \"${PROBE_PREFIX}magisk_dev=1\"")
        appendLine("[ -d /data/adb/magisk ] && echo \"${PROBE_PREFIX}magisk_dir=1\"")
        appendLine("[ -e /dev/.magisk-unblock ] && echo \"${PROBE_PREFIX}magisk_unblock=1\"")
        appendLine("grep -s -q 'zygisk' /data/adb/magisk/zygisk/* /data/adb/modules/*/module.prop 2>/dev/null && echo \"${PROBE_PREFIX}zygisk=1\"")

        // Legacy su binaries: presence alone, but useful as a last resort.
        appendLine("for s in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /debug_ramdisk/su /system/sbin/su /product/bin/su; do")
        appendLine("  [ -x \"\$s\" ] && echo \"${PROBE_PREFIX}su_bin=\$s\"")
        appendLine("done")

        // Installed managers, as a single `pm` call piped through the known ids.
        appendLine("pm list packages 2>/dev/null | sed -n 's/^package://p' | " +
            "grep -E '^(package:)?(${RootProvider.MANAGER_PACKAGES.joinToString("|") { Regex.escape(it) }})\$' | " +
            "sed 's/^package://' | sed 's/^/${PROBE_PREFIX}pkg=/'")

        appendLine("exit 0")
    }

    /** Root state is stable; re-probing is only worth it after a lifecycle change. */
    private const val GRANTED_TTL_MS = 30_000L

    /** A denial may be a prompt the user has not answered yet, so retry aggressively. */
    private const val DENIED_TTL_MS = 2_000L

    private val probeMutex = Mutex()

    private class Cache(val environment: RootEnvironment, val createdAt: Long)

    @Volatile
    private var cached: Cache? = null

    init {
        Shell.enableVerboseLogging = false
        // Single source of truth for the shell config. PoxApplication used to set a
        // second builder; because this object initialises lazily, this one always won
        // and the Application's timeout was dead code.
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setTimeout(15)
                    // Prefer `su --mount-master` so the probe can see the global mount
                    // namespace and read /data/adb/*. libsu falls back to plain `su`.
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
            )
        }
    }

    /** Obtain a root shell ahead of the first UI read so later calls are instant. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell() }
        Unit
    }

    /** Drop cached probe results. Call when returning to the foreground. */
    fun invalidateCache() {
        cached = null
    }

    /**
     * Probe the root environment, reusing a recent result.
     *
     * The previous implementation cached the result forever and nothing ever
     * called `clearRootCache`, so a single denial at startup pinned the app to
     * "Unrooted" for the rest of the process even after the user granted root.
     */
    suspend fun environment(): RootEnvironment = withContext(Dispatchers.IO) {
        probeMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            cached?.let { entry ->
                val ttl = if (entry.environment.granted) GRANTED_TTL_MS else DENIED_TTL_MS
                if (now - entry.createdAt < ttl) return@withLock entry.environment
            }
            val fresh = probe()
            cached = Cache(fresh, now)
            fresh
        }
    }

    private suspend fun probe(): RootEnvironment {
        val shell = runCatching { Shell.getShell() }.getOrNull()
            ?: return RootEnvironment(RootProvider.ROOTLESS, "", false, false, 0, null, emptyMap())
        val granted = shell.isRoot

        val output = runCatching { Shell.cmd(PROBE_SCRIPT).exec() }.getOrNull()?.out.orEmpty()
        val probes = parseProbe(output)

        val packages = probes.entries
            .filter { it.key == "pkg" }
            .map { it.value }
            .toSet()

        val provider = classifyByPackage(packages)
            ?: classifyByMarkers(probes)
            ?: RootProvider.UNKNOWN

        return RootEnvironment(
            provider = provider,
            version = fetchVersion(provider, probes),
            granted = granted,
            zygisk = "zygisk" in probes,
            moduleCount = probes["module_count"]?.toIntOrNull() ?: 0,
            metamodule = probes["metamodule"]?.takeIf { it.isNotBlank() },
            probes = probes
        )
    }

    private fun parseProbe(output: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in output) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(PROBE_PREFIX)) continue
            val body = trimmed.substring(PROBE_PREFIX.length)
            val sep = body.indexOf('=')
            if (sep <= 0) continue
            map[body.substring(0, sep)] = body.substring(sep + 1).trim()
        }
        return map
    }

    /**
     * Manager package ids are authoritative: every framework ships one canonical
     * manager, whereas all KernelSU forks share the same on-disk layout.
     */
    private fun classifyByPackage(packages: Set<String>): RootProvider? {
        if (packages.isEmpty()) return null
        for (provider in RootProvider.values()) {
            if (provider.managerPackages.any { it in packages }) return provider
        }
        return null
    }

    /** Fallback for a rooted device whose manager has been uninstalled. */
    private fun classifyByMarkers(probes: Map<String, String>): RootProvider? = when {
        "ap_dir" in probes || "apd" in probes || "apstat" in probes -> RootProvider.APATCH
        "kp" in probes -> RootProvider.KERNELPATCH
        "ksud" in probes || "ksud_bin" in probes || "ksu_dir" in probes ||
            "ksurc" in probes || "ksu_lkm" in probes -> {
            val bakasu = "adbroot" in probes || "dynamic_mgr" in probes ||
                "module_configs" in probes || "sulogd_lock" in probes
            if (bakasu) RootProvider.BAKASU else RootProvider.KERNELSU
        }
        "magisk_dev" in probes || "magisk_dir" in probes -> RootProvider.MAGISK
        "su_bin" in probes -> RootProvider.SUPERSU
        else -> null
    }

    /**
     * Ask the framework for its own version.
     *
     * The old code ran `cat /data/adb/ksu/bin/ksud -V`, which asks `cat` to print
     * a file named `-V`; the flag is meaningless to it and the version string was
     * never obtained. This executes the daemon instead.
     */
    private suspend fun fetchVersion(provider: RootProvider, probes: Map<String, String>): String {
        if ("uid" !in probes) return ""
        val candidates = when (provider) {
            RootProvider.BAKASU, RootProvider.KERNELSU_NEXT,
            RootProvider.SUKISU_ULTRA, RootProvider.KERNELSU ->
                listOf("/data/adb/ksu/bin/ksud -V", "/data/adb/ksud -V")
            RootProvider.APATCH, RootProvider.KERNELPATCH -> listOf("/data/adb/ap/bin/apd -v")
            RootProvider.MAGISK -> listOf("magisk -v")
            else -> emptyList()
        }
        for (command in candidates) {
            val text = runCatching { Shell.cmd(command).exec() }.getOrNull()
            val line = text?.out?.firstOrNull { it.isNotBlank() }?.trim()
            if (!line.isNullOrEmpty()) return line
        }
        return ""
    }

    /**
     * App-side su probe. Kept for callers that need a cheap pre-check, but it is
     * only a hint: `File.exists()` runs in the app mount namespace, where the
     * `/data/adb` tree is often invisible and a magic-mounted `su` may have no
     * backing file on disk at all.
     */
    fun hasSuBinary(): Boolean = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
        "/debug_ramdisk/su", "/system/sbin/su", "/product/bin/su",
        "/data/adb/ksud", "/data/adb/ksu/bin/ksud", "/data/adb/ap/bin/apd"
    ).any { runCatching { File(it).exists() }.getOrDefault(false) }

    suspend fun isRootAvailable(): Boolean = environment().granted

    suspend fun detectRootProvider(): RootProvider = environment().provider

    suspend fun runCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext ShellResult(
                isSuccess = false,
                output = listOf("Root access is not available"),
                exitCode = -1
            )
        }
        try {
            val result = Shell.cmd(command).exec()
            ShellResult(
                isSuccess = result.isSuccess,
                output = result.out,
                exitCode = result.code
            )
        } catch (e: Exception) {
            ShellResult(
                isSuccess = false,
                output = listOf(e.message ?: "Execution failed"),
                exitCode = -1
            )
        }
    }

    suspend fun fastRead(path: String): String? = withContext(Dispatchers.IO) {
        val quoted = shellQuote(path)

        // /sys and most of /proc are world-readable; skip a shell spawn for them.
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val content = file.bufferedReader().use { it.readText() }.trim()
                if (content.isNotEmpty()) return@withContext content
            }
        } catch (_: Exception) { }

        if (!isRootAvailable()) return@withContext null
        try {
            val res = Shell.cmd("cat $quoted 2>/dev/null").exec()
            if (res.isSuccess && res.out.isNotEmpty()) {
                return@withContext res.out.joinToString("\n").trim()
            }
        } catch (_: Exception) { }
        null
    }

/**
 * Write a value and confirm it landed.
 *
 * Every tuning setter used to discard this return value and report success
 * regardless, so a missing `/proc/perfmgr` node looked identical to a working
 * write. The read-back below is what makes the reported result trustworthy.
 *
 * [verify] receives the node contents after the write. The default is correct for
 * sysfs files, where writing and reading back the same value round-trips. Nodes
 * with a command interface (MediaTek's `usrtch`) echo back a status dump instead,
 * so they must supply their own predicate.
 */
suspend fun fastWrite(
    path: String,
    value: String,
    verify: ((String) -> Boolean)? = null
): Boolean = withContext(Dispatchers.IO) {
    val quotedPath = shellQuote(path)
    val expected = value.trim()

    try {
        val file = File(path)
        if (file.exists() && file.canWrite()) {
            file.writeText(value)
            if (verify == null && file.readText().trim() == expected) return@withContext true
        }
    } catch (_: Exception) { }

    if (!isRootAvailable()) return@withContext false

    try {
        val written = Shell.cmd("printf '%s\\n' ${shellQuote(value)} > $quotedPath").exec()
        if (!written.isSuccess) return@withContext false

        val readBack = Shell.cmd("cat $quotedPath 2>/dev/null").exec()
        val content = readBack.out.joinToString("\n")
        if (verify != null) return@withContext verify(content)
        readBack.out.firstOrNull()?.trim() == expected
    } catch (_: Exception) {
        false
    }
}

/**
 * Read a node through the root shell, bypassing the app mount namespace.
 *
 * `/proc/perfmgr` and `/data/adb` are frequently invisible to plain `File` reads
 * on a device with a root framework installed, so procfs and the kernel-supplied
 * tuning nodes must always be read through the shell.
 */
suspend fun readNode(path: String): String? = withContext(Dispatchers.IO) {
    if (!isRootAvailable()) return@withContext null
    try {
        val res = Shell.cmd("cat ${shellQuote(path)} 2>/dev/null").exec()
        val text = res.out.joinToString("\n").trim()
        if (text.isEmpty()) null else text
    } catch (_: Exception) {
        null
    }
}

/** True when the node exists and is writable by the current root shell. */
suspend fun nodeExists(path: String): Boolean = withContext(Dispatchers.IO) {
    if (!isRootAvailable()) return@withContext false
    try {
        Shell.cmd("test -w ${shellQuote(path)}").exec().isSuccess
    } catch (_: Exception) {
        false
    }
}

    /** Single-quote a value for safe interpolation into a shell command. */
    fun quote(value: String): String = shellQuote(value)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
 * Whether the kernel is unmounting modules on demand.
 *
 * `/dev/nomount` never existed. KernelSU and its forks implement this as feature
 * id 1 (`KernelUmount`) inside the kernel's feature-handler interface, queried
 * through the ksud CLI, which prints `Value: <0|1>` and `Status: enabled|disabled`.
 */
suspend fun detectNoMount(): Boolean = withContext(Dispatchers.IO) {
    if (!isRootAvailable()) return@withContext false

    val result = runCatching {
        Shell.cmd("/data/adb/ksu/bin/ksud feature get kernel_umount 2>/dev/null").exec()
    }.getOrNull() ?: return@withContext false

    if (!result.isSuccess) return@withContext false

    val text = result.out.joinToString("\n")
    // "Status: enabled" is authoritative; fall back to the raw value.
    if (text.contains("enabled", ignoreCase = true) && !text.contains("disabled", ignoreCase = true)) {
        return@withContext true
    }
    Regex("(?m)^Value:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() == 1
}

    suspend fun detectSELinux(): String = withContext(Dispatchers.IO) {
        try {
            val file = File("/sys/fs/selinux/enforce")
            if (file.exists() && file.canRead()) {
                val content = file.bufferedReader().use { it.readText() }.trim()
                return@withContext if (content == "0") "Permissive" else "Enforcing"
            }
        } catch (_: Exception) { }

        if (isRootAvailable()) {
            try {
                val res = Shell.cmd("getenforce 2>/dev/null").exec()
                val text = res.out.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) return@withContext text
            } catch (_: Exception) { }
        }
        "Enforcing"
    }
}