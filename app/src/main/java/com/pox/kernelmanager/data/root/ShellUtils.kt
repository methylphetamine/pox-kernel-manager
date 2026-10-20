package com.pox.kernelmanager.data.root

import android.os.SystemClock
import com.pox.kernelmanager.data.model.RootProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

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

        // Magisk: running daemon and version check
        appendLine("MAGISK_V=\$(magisk -v 2>/dev/null)")
        appendLine("[ -n \"\$MAGISK_V\" ] && echo \"${PROBE_PREFIX}magisk_ver=\$MAGISK_V\"")
        appendLine("[ -e /dev/.magisk ] && echo \"${PROBE_PREFIX}magisk_dev=1\"")
        appendLine("[ -d /data/adb/magisk ] && echo \"${PROBE_PREFIX}magisk_dir=1\"")
        appendLine("[ -e /dev/.magisk-unblock ] && echo \"${PROBE_PREFIX}magisk_unblock=1\"")
        appendLine("pidof magiskd >/dev/null 2>&1 && echo \"${PROBE_PREFIX}magiskd_running=1\"")

        // KernelPatch family: FolkPatch / APatch / bare KernelPatch all expose /dev/kp.
        // `fpd` only exists in FolkPatch; apd/ap_dir are shared with APatch.
        appendLine("[ -e /dev/kp ] && echo \"${PROBE_PREFIX}kp=1\"")
        appendLine("[ -d /data/adb/ap ] && echo \"${PROBE_PREFIX}ap_dir=1\"")
        appendLine("[ -x /data/adb/ap/bin/apd ] && echo \"${PROBE_PREFIX}apd=1\"")
        appendLine("[ -x /data/adb/ap/bin/fpd ] && echo \"${PROBE_PREFIX}fpd=1\"")
        appendLine("[ -x /data/adb/fp/bin/fpd ] && echo \"${PROBE_PREFIX}fpd=1\"")
        appendLine("APD_V=\$(/data/adb/ap/bin/apd -V 2>/dev/null || /data/adb/apd -V 2>/dev/null)")
        appendLine("[ -n \"\$APD_V\" ] && echo \"${PROBE_PREFIX}apd_ver=\$APD_V\"")
        appendLine("[ -x /data/adb/ap/bin/apstat ] && echo \"${PROBE_PREFIX}apstat=1\"")
        appendLine("[ -d /data/adb/ap/modules ] && echo \"${PROBE_PREFIX}ap_modules=1\"")

        // KernelSU lineage: REQUIRES active /sys/module/kernelsu driver
        appendLine("[ -e /sys/module/kernelsu ] && echo \"${PROBE_PREFIX}ksu_lkm=1\"")
        appendLine("[ -x /data/adb/ksud ] && echo \"${PROBE_PREFIX}ksud=1\"")
        appendLine("[ -x /data/adb/ksu/bin/ksud ] && echo \"${PROBE_PREFIX}ksud_bin=1\"")
        appendLine("[ -d /data/adb/ksu ] && echo \"${PROBE_PREFIX}ksu_dir=1\"")
        appendLine("[ -e /data/adb/ksu/.ksurc ] && echo \"${PROBE_PREFIX}ksurc=1\"")
        appendLine("[ -e /data/adb/ksu/lib/libadbroot.so ] && echo \"${PROBE_PREFIX}adbroot=1\"")
        appendLine("[ -e /data/adb/ksu/.dynamic_manager ] && echo \"${PROBE_PREFIX}dynamic_mgr=1\"")
        appendLine("[ -d /data/adb/ksu/module_configs ] && echo \"${PROBE_PREFIX}module_configs=1\"")
        appendLine("[ -e /data/adb/ksu/sulogd.lock ] && echo \"${PROBE_PREFIX}sulogd_lock=1\"")
        appendLine("KSUD_V=\$(/data/adb/ksu/bin/ksud -V 2>/dev/null || /data/adb/ksud -V 2>/dev/null)")
        appendLine("[ -n \"\$KSUD_V\" ] && echo \"${PROBE_PREFIX}ksud_ver=\$KSUD_V\"")

        // Modules & Zygisk
        appendLine("grep -s -q 'zygisk' /data/adb/magisk/zygisk/* /data/adb/modules/*/module.prop 2>/dev/null && echo \"${PROBE_PREFIX}zygisk=1\"")
        appendLine("echo \"${PROBE_PREFIX}module_count=\$(ls -1 /data/adb/modules 2>/dev/null | wc -l | tr -d ' ')\"")
        appendLine("echo \"${PROBE_PREFIX}metamodule=\$(ls -1 /data/adb/metamodule 2>/dev/null | head -n 1)\"")

        // Legacy su binaries
        appendLine("for s in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /debug_ramdisk/su /system/sbin/su /product/bin/su; do")
        appendLine("  [ -x \"\$s\" ] && echo \"${PROBE_PREFIX}su_bin=\$s\"")
        appendLine("done")

        // Installed managers
        appendLine("pm list packages 2>/dev/null | sed -n 's/^package://p' | " +
            "grep -E '^(package:)?(${RootProvider.MANAGER_PACKAGES.joinToString("|") { it.replace(".", "\\.") }})\$' | " +
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
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setTimeout(15)
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

        val provider = classifyRoot(probes, packages)

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

    private fun classifyRoot(probes: Map<String, String>, packages: Set<String>): RootProvider {
        val hasMagisk = "magisk_ver" in probes || "magiskd_running" in probes || "magisk_dev" in probes
        val hasKp = "kp" in probes
        val hasKsuDriver = "ksu_lkm" in probes

        // 1. If Magisk daemon is running and kernel has no KP or KSU driver active, it is Magisk
        if (hasMagisk && !hasKp && !hasKsuDriver) {
            return RootProvider.MAGISK
        }

        // 2. KernelPatch device: FolkPatch / APatch / bare KernelPatch. FolkPatch keeps
        //    APatch's /data/adb/ap layout, so `fpd` and the manager package discriminate.
        if (hasKp) {
            val folkpatch = "fpd" in probes || "me.yuki.folk" in packages
            val apatch = "apd" in probes || "ap_dir" in probes || "me.bmax.apatch" in packages
            return when {
                folkpatch -> RootProvider.FOLKPATCH
                apatch -> RootProvider.APATCH
                else -> RootProvider.KERNELPATCH
            }
        }

        // 3. An installed manager APK outranks kernel-side markers: every KernelSU fork
        //    shares /data/adb/ksu, so leftover BakaSU marker files (or a leftover fork
        //    install while another manager grants su) used to relabel the active manager.
        classifyByPackage(packages)?.let { return it }

        // 4. KernelSU driver: fork name from `ksud -V`, then fork-specific files.
        if (hasKsuDriver) {
            classifyKernelSu(probes, probes["ksud_ver"])?.let { return it }
            return RootProvider.KERNELSU
        }

        // 5. If Magisk is present in any form
        if (hasMagisk || "magisk_dir" in probes || "com.topjohnwu.magisk" in packages) {
            return RootProvider.MAGISK
        }

        // 6. Fallback to markers
        return classifyByMarkers(probes) ?: RootProvider.UNKNOWN
    }

    /**
     * Tells KernelSU forks apart from the name printed by `ksud -V` (BakaSU reports
     * "ReSukiSU", which also contains "suki", so the more specific names are matched
     * first), falling back to BakaSU's marker files inside /data/adb/ksu.
     */
    private fun classifyKernelSu(probes: Map<String, String>, versionText: String?): RootProvider? {
        val name = versionText?.lowercase().orEmpty()
        if (name.isNotEmpty()) {
            return when {
                "baka" in name || "resuki" in name -> RootProvider.BAKASU
                "next" in name -> RootProvider.KERNELSU_NEXT
                "suki" in name -> RootProvider.SUKISU_ULTRA
                "kernel" in name -> RootProvider.KERNELSU
                else -> null
            }
        }
        val bakasu = "adbroot" in probes || "dynamic_mgr" in probes
        return if (bakasu) RootProvider.BAKASU else null
    }

    private fun classifyByPackage(packages: Set<String>): RootProvider? {
        if (packages.isEmpty()) return null
        for (provider in RootProvider.values()) {
            if (provider.managerPackages.any { it in packages }) return provider
        }
        return null
    }

    private fun classifyByMarkers(probes: Map<String, String>): RootProvider? = when {
        "kp" in probes -> when {
            "fpd" in probes -> RootProvider.FOLKPATCH
            "apd" in probes || "ap_dir" in probes -> RootProvider.APATCH
            else -> RootProvider.KERNELPATCH
        }
        "ksu_lkm" in probes -> classifyKernelSu(probes, probes["ksud_ver"]) ?: RootProvider.KERNELSU
        "magisk_dev" in probes || "magisk_dir" in probes || "magisk_ver" in probes -> RootProvider.MAGISK
        "su_bin" in probes -> RootProvider.SUPERSU
        else -> null
    }

    private suspend fun fetchVersion(provider: RootProvider, probes: Map<String, String>): String {
        if ("uid" !in probes) return ""
        // Versions sampled in the batched probe first; the extra round-trips below are
        // only needed when that command was not available.
        when (provider) {
            RootProvider.FOLKPATCH, RootProvider.APATCH, RootProvider.KERNELPATCH ->
                probes["apd_ver"]?.takeIf { it.isNotBlank() }?.let { return it }
            RootProvider.BAKASU, RootProvider.KERNELSU_NEXT,
            RootProvider.SUKISU_ULTRA, RootProvider.KERNELSU ->
                probes["ksud_ver"]?.takeIf { it.isNotBlank() }?.let { return it }
            else -> Unit
        }
        if (provider == RootProvider.MAGISK && probes["magisk_ver"] != null) {
            return probes["magisk_ver"]!!
        }
        val candidates = when (provider) {
            RootProvider.FOLKPATCH, RootProvider.APATCH,
            RootProvider.KERNELPATCH -> listOf("/data/adb/ap/bin/apd -V", "/data/adb/apd -V")
            RootProvider.BAKASU, RootProvider.KERNELSU_NEXT,
            RootProvider.SUKISU_ULTRA, RootProvider.KERNELSU ->
                listOf("/data/adb/ksu/bin/ksud -V", "/data/adb/ksud -V")
            RootProvider.MAGISK -> listOf("magisk -v", "magisk -V")
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

    /**
     * Run a long-running root command and stream every output line as it arrives.
     *
     * libsu appends to the supplied lists while the job is still running, so a
     * small polling loop lets the flasher show install progress instead of a
     * silent spinner. STDOUT and STDERR share one list: AnyKernel3 prints its
     * useful diagnostics on both streams.
     */
    suspend fun runCommandStreaming(
        command: String,
        onLine: (String) -> Unit
    ): ShellResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext ShellResult(
                isSuccess = false,
                output = listOf("Root access is not available"),
                exitCode = -1
            )
        }

        try {
            val output = Collections.synchronizedList(mutableListOf<String>())
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                return@withContext ShellResult(
                    isSuccess = false,
                    output = listOf("Root access was not granted"),
                    exitCode = -1
                )
            }

            val job = shell.newJob().add(command).to(output, output)
            coroutineScope {
                val running = async(Dispatchers.IO) { job.exec() }
                var emitted = 0
                while (!running.isCompleted) {
                    emitted = drainOutput(output, emitted, onLine)
                    delay(80)
                }
                emitted = drainOutput(output, emitted, onLine)
                val result = running.await()
                ShellResult(
                    isSuccess = result.isSuccess,
                    output = synchronized(output) { output.toList() },
                    exitCode = result.code
                )
            }
        } catch (e: Exception) {
            ShellResult(
                isSuccess = false,
                output = listOf(e.message ?: "Execution failed"),
                exitCode = -1
            )
        }
    }

    private fun drainOutput(
        lines: MutableList<String>,
        start: Int,
        onLine: (String) -> Unit
    ): Int {
        val snapshot = synchronized(lines) { lines.toList() }
        var index = start
        while (index < snapshot.size) {
            runCatching { onLine(snapshot[index]) }
            index++
        }
        return index
    }

    suspend fun fastRead(path: String): String? = withContext(Dispatchers.IO) {
        val quoted = shellQuote(path)

        // Only attempt direct File read for world-readable paths that won't trigger SELinux denial audits
        try {
            if (!path.startsWith("/sys/kernel/") && !path.startsWith("/proc/sys/kernel/")) {
                val file = File(path)
                if (file.canRead()) {
                    val content = file.bufferedReader().use { it.readText() }.trim()
                    if (content.isNotEmpty()) return@withContext content
                }
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
    if (!rootKnownGranted()) return@withContext null
    try {
        val res = Shell.cmd("cat ${shellQuote(path)} 2>/dev/null").exec()
        val text = res.out.joinToString("\n").trim()
        if (text.isEmpty()) null else text
    } catch (_: Exception) {
        null
    }
}

/** One probed kernel node and what it returned. */
data class NodeStatus(
    val path: String,
    val exists: Boolean,
    val writable: Boolean,
    val sample: String?
)

/**
 * Every node this build looks for, with what it actually returned.
 *
 * Exposed so the diagnostics screen can show the real state of the device
 * instead of a guess, and so someone reporting "everything is greyed out" can
 * share the raw output.
 */
suspend fun nodeReport(paths: List<String>): List<NodeStatus> =
    withContext(Dispatchers.IO) {
        val granted = rootKnownGranted()
        paths.map { path ->
            if (!granted) {
                NodeStatus(path, exists = false, writable = false, sample = null)
            } else {
                val exists = runCatching {
                    Shell.cmd("test -e ${shellQuote(path)}").exec().isSuccess
                }.getOrDefault(false)
                val writable = runCatching {
                    Shell.cmd("test -w ${shellQuote(path)}").exec().isSuccess
                }.getOrDefault(false)
                val sample = runCatching {
                    Shell.cmd("cat ${shellQuote(path)} 2>/dev/null | head -c 120").exec()
                        .out.joinToString(" ").trim()
                }.getOrNull()
                NodeStatus(path, exists, writable, sample?.takeIf { it.isNotEmpty() })
            }
        }
    }

/**
 * Whether the last probe found a root shell, without forcing a re-probe.
 *
 * Every caller of `nodeExists`/`readNode` already establishes the environment
 * once, so re-entering `environment()` on each of ~12 probes re-ran the whole
 * batched script — including `pm list packages` — every couple of seconds while
 * the tuning screen sat there loading.
 */
fun rootKnownGranted(): Boolean = cached?.environment?.granted == true

suspend fun nodeExists(path: String): Boolean = withContext(Dispatchers.IO) {
    if (!rootKnownGranted()) return@withContext false
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