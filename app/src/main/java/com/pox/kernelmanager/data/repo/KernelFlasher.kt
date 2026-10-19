package com.pox.kernelmanager.data.repo

import android.os.Environment
import android.os.Process
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.root.ShellResult
import com.pox.kernelmanager.data.root.ShellUtils
import com.pox.kernelmanager.data.storage.PoxPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Which root bundle a kernel package carries (shown before flashing). */
enum class KernelZipFlavor(val label: String) {
    FOLKPATCH("FolkPatch"),
    APATCH("APatch"),
    KERNELSU("KernelSU"),
    GENERIC("Kernel package");

    val detail: String
        get() = when (this) {
            FOLKPATCH -> "bundles FolkPatch - installed during this flash"
            APATCH -> "bundles APatch - installed during this flash"
            KERNELSU -> "KernelSU recovery package"
            GENERIC -> "AnyKernel3 recovery package"
        }
}

/** A kernel zip the user picked (or downloaded) and the app has inspected. */
data class KernelZipInfo(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val flavor: KernelZipFlavor
)

/**
 * One boot + dtbo backup set.
 *
 * The set is the bootloop insurance for a kernel flash: `boot.img` and
 * `dtbo.img` as standalone partition dumps plus a generated PoxRestore zip
 * that writes both back when flashed in TWRP / OrangeFox.
 */
data class BootBackupSet(
    val id: String,
    val bootSizeBytes: Long,
    val dtboSizeBytes: Long,
    val zipName: String,
    /** Downloads-relative folder, or null when the media-store publish failed. */
    val downloadPath: String?,
    /** /data/local/tmp folder holding the recovery-side mirror. */
    val dePath: String,
    val createdAt: Long
) {
    val hasDtbo: Boolean get() = dtboSizeBytes > 0L
}

/** A package staged for the next recovery boot. */
data class StagedPackage(
    val name: String,
    val destination: String,
    val backup: BootBackupSet?
)

/**
 * A recovery action the app queued, as remembered across process restarts.
 *
 * It exists so the flasher can tell "waiting for the next reboot" apart from
 * "a recovery boot happened and the script was never picked up" - the script
 * file surviving a boot means the recovery did not read it.
 */
data class PendingScript(
    val target: String,
    val startedAt: Long
)

/**
 * Recovery-based flashing engine.
 *
 * There is deliberately no attempt to flash a boot image from the running
 * system: the Pox zips are AnyKernel3 packages whose installer patches the
 * ramdisk and runs the root setup, and that installer needs a recovery
 * environment. What the app does instead is what the well-known flash apps
 * do: stage the package, write an OpenRecoveryScript, and let TWRP /
 * OrangeFox run the real installer on the next recovery boot.
 *
 * Backup layout (per set, id = yyyyMMdd-HHmmss):
 *
 *   /sdcard/Download/PoxBackups/<id>/boot.img
 *   /sdcard/Download/PoxBackups/<id>/dtbo.img
 *   /sdcard/Download/PoxBackups/<id>/PoxRestore-<id>.zip   (contains both)
 *   /data/local/tmp/PoxBackups/<id>/PoxRestore-<id>.zip    (recovery mirror)
 *
 * The Downloads copy is what the user can grab and flash by hand. The mirror
 * lives on /data/local/tmp because that is device-encrypted storage: recovery
 * can read it even when /sdcard cannot be decrypted, which is exactly the
 * bootloop situation the backup exists for.
 */
object KernelFlasher {

    private const val DE_BACKUP_ROOT = "/data/local/tmp/PoxBackups"
    private const val DE_FLASH_DIR = "/data/local/tmp/PoxFlash"
    private const val DOWNLOAD_SUBDIR = "PoxBackups"
    private const val KEEP_BACKUPS = 3
    private const val KEEP_STAGED = 3

    /** TWRP looks here first when its fstab has a /cache partition (begonia does). */
    private const val CACHE_ORS = "/cache/recovery/openrecoveryscript"

    /** The cacheless path modern TWRP falls back to. */
    private const val DATA_ORS = "/data/cache/recovery/openrecoveryscript"

    private const val PREFS = "pox_flasher"
    private const val KEY_PENDING_TARGET = "pending_ors_target"
    private const val KEY_PENDING_TIME = "pending_ors_time"

    private val SET_ID_REGEX = Regex("^[0-9]{8}-[0-9]{6}$")

    // ------------------------------------------------------------------ root

    private suspend fun requireRoot() {
        if (!ShellUtils.environment().granted) {
            throw IllegalStateException("Root access is required. Grant the su request and try again.")
        }
    }

    private fun q(value: String): String = ShellUtils.shellQuote(value)

    private suspend fun sh(command: String): ShellResult = ShellUtils.runCommand(command)

    private fun ShellResult.orThrow(action: String): ShellResult {
        if (!isSuccess) {
            val detail = output.lastOrNull { it.isNotBlank() }?.trim()
            throw IllegalStateException(if (detail.isNullOrEmpty()) action else "$action - $detail")
        }
        return this
    }

    // -------------------------------------------------------------- inspection

    /**
     * Confirms a zip is an AnyKernel3 kernel package before the user can flash
     * it, and works out which root flavour it carries from the file name first
     * (the CI names are unambiguous) and the installer header second.
     */
    suspend fun inspect(file: File, displayName: String): KernelZipInfo = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            throw IllegalArgumentException("The selected file is empty")
        }
        val entries = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        if (entries.none { it == "anykernel.sh" || it.endsWith("/anykernel.sh") }) {
            throw IllegalArgumentException("This zip is not a kernel installer - anykernel.sh is missing")
        }
        if (entries.none { name ->
                val base = name.substringAfterLast('/')
                base.startsWith("Image") || base.startsWith("zImage")
            }) {
            throw IllegalArgumentException("This zip has no kernel image inside")
        }
        KernelZipInfo(
            file = file,
            displayName = displayName,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            flavor = detectFlavor(file, displayName)
        )
    }

    private fun detectFlavor(file: File, displayName: String): KernelZipFlavor {
        val name = displayName.lowercase(Locale.US)
        if (name.contains("folkpatch")) return KernelZipFlavor.FOLKPATCH
        if (name.contains("apatch")) return KernelZipFlavor.APATCH
        if (name.contains("kernelsu") || name.contains("ksu")) return KernelZipFlavor.KERNELSU

        val header = runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("anykernel.sh") ?: return@use ""
                zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            }
        }.getOrDefault("")
        return when {
            header.contains("me.yuki.folk") -> KernelZipFlavor.FOLKPATCH
            header.contains("me.bmax.apatch") -> KernelZipFlavor.APATCH
            header.contains("kernelsu", ignoreCase = true) || header.contains("ksud") -> KernelZipFlavor.KERNELSU
            else -> KernelZipFlavor.GENERIC
        }
    }

    // ---------------------------------------------------------------- backups

    /** Well-known boot / dtbo block paths for this device, in preference order. */
    suspend fun resolvePartitions(): Map<String, String> = withContext(Dispatchers.IO) {
        val script = buildString {
            appendLine("for d in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/bootdevice/by-name; do")
            appendLine("  [ -e \"\$d/boot\" ] && echo \"boot=\$d/boot\"")
            appendLine("  [ -e \"\$d/dtbo\" ] && echo \"dtbo=\$d/dtbo\"")
            appendLine("done")
            appendLine("true")
        }
        sh(script).output
            .mapNotNull { line ->
                val sep = line.indexOf('=')
                if (sep <= 0) null else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }
            .toMap()
    }

    /**
     * Takes a full boot + dtbo backup set and returns it once both copies
     * (Downloads and the recovery mirror) are verified. Used before every
     * flash, and by the "Create backup now" action.
     */
    suspend fun createBackup(onStep: suspend (String) -> Unit): BootBackupSet =
        withContext(Dispatchers.IO) {
            requireRoot()
            val partitions = resolvePartitions()
            val bootDevice = partitions["boot"]
                ?: throw IllegalStateException("This device does not expose a boot partition")
            val dtboDevice = partitions["dtbo"]

            val setId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val remoteDir = "$DE_BACKUP_ROOT/$setId"

            onStep("Preparing the backup folder")
            sh("mkdir -p ${q(remoteDir)}").orThrow("Could not create the backup folder")

            onStep("Dumping the boot partition")
            dumpPartition(bootDevice, "$remoteDir/boot.img")
            if (dtboDevice != null) {
                onStep("Dumping the dtbo partition")
                dumpPartition(dtboDevice, "$remoteDir/dtbo.img")
            }
            sh("sync")

            val bootSize = shellFileSize("$remoteDir/boot.img")
                ?: throw IllegalStateException("Could not measure the boot dump")
            val bootSha = shellSha256("$remoteDir/boot.img")
                ?: throw IllegalStateException("Could not hash the boot dump")
            val dtboSize = if (dtboDevice != null) shellFileSize("$remoteDir/dtbo.img") ?: 0L else 0L
            val dtboSha = if (dtboSize > 0L) shellSha256("$remoteDir/dtbo.img") else null

            // Android has no zip binary, so the package is assembled in the app.
            // The dumps are pulled into the app cache first (root writes them
            // back with app ownership and labels so the app can read them).
            val cacheSet = File(PoxApplication.instance.cacheDir, "poxbackup/$setId").apply { mkdirs() }
            val cacheBoot = File(cacheSet, "boot.img")
            val cacheDtbo = File(cacheSet, "dtbo.img").takeIf { dtboSha != null }

            onStep("Reading the backup images")
            pullToCache("$remoteDir/boot.img", cacheBoot)
            cacheDtbo?.let { pullToCache("$remoteDir/dtbo.img", it) }

            onStep("Building the restore package")
            val zipName = "PoxRestore-$setId.zip"
            val cacheZip = File(cacheSet, zipName)
            buildRestoreZip(cacheZip, cacheBoot, cacheDtbo, bootSha, dtboSha, setId)

            // Publish the user-facing set. A media-store hiccup must not abort
            // the flash: the verified mirror below is the recovery-side copy.
            var downloadPath: String? = null
            val folder = "$DOWNLOAD_SUBDIR/$setId"
            onStep("Saving to Downloads/$folder")
            try {
                DownloadsStore.writeStream(folder, "boot.img", "application/octet-stream", cacheBoot)
                cacheDtbo?.let { DownloadsStore.writeStream(folder, "dtbo.img", "application/octet-stream", it) }
                DownloadsStore.writeStream(folder, zipName, "application/zip", cacheZip)
                downloadPath = "${Environment.DIRECTORY_DOWNLOADS}/$folder"
            } catch (e: Exception) {
                runCatching { DownloadsStore.deleteUnder(folder) }
                onStep("Downloads copy failed - keeping the recovery copy (${e.message})")
            }

            onStep("Mirroring the restore package")
            sh("cp -f ${q(cacheZip.absolutePath)} ${q("$remoteDir/$zipName")}")
                .orThrow("Could not mirror the restore package")
            val mirroredSha = shellSha256("$remoteDir/$zipName")
            if (mirroredSha == null || !mirroredSha.equals(sha256(cacheZip), ignoreCase = true)) {
                sh("rm -rf ${q(remoteDir)}")
                throw IllegalStateException("The restore package copy failed verification")
            }

            writeSetInfo(remoteDir, setId, bootSize, bootSha, dtboSize, dtboSha, zipName, downloadPath)
            onStep("Backup set $setId saved")
            pruneBackups()
            cacheSet.deleteRecursively()

            BootBackupSet(
                id = setId,
                bootSizeBytes = bootSize,
                dtboSizeBytes = dtboSize,
                zipName = zipName,
                downloadPath = downloadPath,
                dePath = remoteDir,
                createdAt = System.currentTimeMillis()
            )
        }

    /** Every backup set on the device, newest first. */
    suspend fun listBackups(): List<BootBackupSet> = withContext(Dispatchers.IO) {
        val command = buildString {
            appendLine("ls -1 ${q(DE_BACKUP_ROOT)} 2>/dev/null | while read d; do")
            appendLine("  [ -f \"$DE_BACKUP_ROOT/\$d/set.info\" ] || continue")
            appendLine("  echo \"##\$d\"")
            appendLine("  cat \"$DE_BACKUP_ROOT/\$d/set.info\" 2>/dev/null")
            appendLine("done")
            appendLine("true")
        }
        val sets = mutableListOf<BootBackupSet>()
        var id: String? = null
        var fields = mutableMapOf<String, String>()

        fun flush() {
            val currentId = id ?: return
            val zipName = fields["zip"] ?: return
            if (!SET_ID_REGEX.matches(currentId)) return
            sets += BootBackupSet(
                id = currentId,
                bootSizeBytes = fields["boot.size"]?.toLongOrNull() ?: 0L,
                dtboSizeBytes = fields["dtbo.size"]?.toLongOrNull() ?: 0L,
                zipName = zipName,
                downloadPath = fields["download"]?.takeIf { it.isNotBlank() },
                dePath = "$DE_BACKUP_ROOT/$currentId",
                createdAt = (fields["created"]?.toLongOrNull() ?: 0L) * 1000L
            )
        }

        for (raw in sh(command).output) {
            val line = raw.trim()
            when {
                line.startsWith("##") -> {
                    flush()
                    id = line.substring(2)
                    fields = mutableMapOf()
                }
                '=' in line -> {
                    val sep = line.indexOf('=')
                    fields[line.substring(0, sep)] = line.substring(sep + 1)
                }
            }
        }
        flush()
        sets.sortedByDescending { it.id }
    }

    /** Deletes both copies (Downloads and the recovery mirror) of a set. */
    suspend fun deleteBackup(set: BootBackupSet): Unit = withContext(Dispatchers.IO) {
        requireRoot()
        sh("rm -rf ${q(set.dePath)}").orThrow("Could not delete the backup")
        DownloadsStore.deleteUnder("$DOWNLOAD_SUBDIR/${set.id}")
    }

    /**
     * Stages the set's PoxRestore zip and queues it as the next recovery
     * action. The package writes boot + dtbo back when the recovery runs it.
     */
    suspend fun stageRestore(set: BootBackupSet, onStep: suspend (String) -> Unit): StagedPackage =
        withContext(Dispatchers.IO) {
            requireRoot()
            onStep("Locating the restore package")
            val deZip = "${set.dePath}/${set.zipName}"
            val sdcardZip = "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/${set.id}/${set.zipName}"
            val source = when {
                sh("test -f ${q(deZip)}").isSuccess -> deZip
                sh("test -f ${q(sdcardZip)}").isSuccess -> sdcardZip
                else -> throw IllegalStateException("The restore package for set ${set.id} is missing")
            }
            onStep("Queuing the recovery install")
            writeRecoveryScript(source)
            StagedPackage(name = set.zipName, destination = source, backup = set)
        }

    // ------------------------------------------------------------------ flash

    /**
     * The full flash pipeline: a verified backup set first (no exceptions -
     * see [createBackup]), then the package staged into recovery-readable
     * storage, then the OpenRecoveryScript that installs it on the next
     * recovery boot.
     */
    suspend fun stageKernelZip(
        zip: File,
        displayName: String,
        onStep: suspend (String) -> Unit
    ): StagedPackage = withContext(Dispatchers.IO) {
        requireRoot()

        // Back up before anything flash-related touches the device. If the new
        // kernel bootloops, this set is what recovery restores from.
        val backup = createBackup(onStep)

        onStep("Staging the kernel package")
        val safeName = sanitizeName(displayName)
        val destination = "$DE_FLASH_DIR/$safeName"
        sh("mkdir -p ${q(DE_FLASH_DIR)}")
        sh("cp -f ${q(zip.absolutePath)} ${q(destination)}").orThrow("Could not stage the kernel package")
        sh("sync")

        val expected = sha256(zip)
        val staged = shellSha256(destination)
        if (staged == null || !staged.equals(expected, ignoreCase = true)) {
            sh("rm -f ${q(destination)}")
            throw IllegalStateException("The staged package failed verification")
        }

        onStep("Queuing the recovery install")
        writeRecoveryScript(destination)
        pruneStaged()

        StagedPackage(name = safeName, destination = destination, backup = backup)
    }

    /**
     * Writes the OpenRecoveryScript TWRP / OrangeFox runs on the next recovery
     * boot.
     *
     * The recovery reads `/cache/recovery/openrecoveryscript` when a cache
     * partition is in its fstab and `/data/cache/recovery/openrecoveryscript`
     * when not (begonia's recovery trees carry /cache). Android usually leaves
     * the cache partition unmounted, so the app mounts it itself first; when
     * that fails the /data/cache location is used instead. Only ONE of the two
     * is ever written - a second copy would fire again on the boot after.
     */
    private suspend fun writeRecoveryScript(installPath: String) {
        val line = "install $installPath"
        val wrote = writeOrs(CACHE_ORS, line, cachePartition = true)
            || writeOrs(DATA_ORS, line, cachePartition = false)
        if (!wrote) {
            throw IllegalStateException("No recovery script location accepted the flash - is TWRP or OrangeFox installed?")
        }
        prefs().edit()
            .putString(KEY_PENDING_TARGET, installPath)
            .putLong(KEY_PENDING_TIME, System.currentTimeMillis())
            .apply()
    }

    private suspend fun writeOrs(target: String, line: String, cachePartition: Boolean): Boolean {
        var mountedByUs = false
        if (cachePartition) {
            if (!isCacheMounted()) {
                if (!mountCache()) return false
                mountedByUs = true
            }
            // No-op when the partition is already writable.
            sh("mount -o rw,remount /cache 2>/dev/null")
        }
        try {
            val dir = target.substringBeforeLast('/')
            if (!sh("mkdir -p ${q(dir)}").isSuccess) return false
            // Whichever path is written replaces anything queued before, so a
            // stale script can never fire on its own.
            sh("rm -f ${q(target)} 2>/dev/null")
            if (!sh("printf '%s\\n' ${q(line)} > ${q(target)}").isSuccess) return false
            sh("sync")
            return sh("cat ${q(target)} 2>/dev/null").output.any { it.trim() == line }
        } finally {
            if (mountedByUs) sh("umount /cache 2>/dev/null")
        }
    }

    private suspend fun isCacheMounted(): Boolean =
        sh("grep -q ' /cache ' /proc/mounts 2>/dev/null").isSuccess

    /** Mounts the cache partition rw so the ORS file can be placed on it. */
    private suspend fun mountCache(): Boolean {
        val device = firstExisting(
            "/dev/block/by-name/cache",
            "/dev/block/bootdevice/by-name/cache",
            "/dev/block/platform/bootdevice/by-name/cache"
        ) ?: return false
        sh("mkdir -p /cache")
        val attempts = listOf(
            "mount -t ext4 -o rw ${q(device)} /cache",
            "mount -t f2fs -o rw ${q(device)} /cache",
            "mount -o rw ${q(device)} /cache"
        )
        for (attempt in attempts) {
            if (sh(attempt).isSuccess && isCacheMounted()) return true
        }
        return false
    }

    private suspend fun firstExisting(vararg paths: String): String? =
        paths.firstOrNull { sh("test -e ${q(it)}").isSuccess }

    /**
     * The queued recovery action, or null when nothing is pending or the
     * script is gone (meaning a recovery consumed it).
     *
     * App callers should suppress the result while the current boot predates
     * no reboot: see FlashViewModel - a script is only "missed" once it has
     * survived a boot.
     */
    suspend fun pendingScript(): PendingScript? = withContext(Dispatchers.IO) {
        val target = prefs().getString(KEY_PENDING_TARGET, null) ?: return@withContext null
        val startedAt = prefs().getLong(KEY_PENDING_TIME, 0L)
        if (startedAt <= 0L) return@withContext null

        // Without a root shell the script file can be neither read nor cleared
        // (every command below silently fails), so the record must be left
        // alone - treating "cannot look" as "not there" would erase the pending
        // state on a denied prompt.
        if (!ShellUtils.rootKnownGranted()) return@withContext null

        val line = "install $target"
        var found = sh("cat ${q(DATA_ORS)} 2>/dev/null").output.any { it.trim() == line }
        if (!found) {
            var mountedByUs = false
            if (!isCacheMounted()) {
                if (mountCache()) mountedByUs = true
            }
            try {
                found = sh("cat ${q(CACHE_ORS)} 2>/dev/null").output.any { it.trim() == line }
            } finally {
                if (mountedByUs) sh("umount /cache 2>/dev/null")
            }
        }
        if (!found) {
            prefs().edit().remove(KEY_PENDING_TARGET).remove(KEY_PENDING_TIME).apply()
            null
        } else {
            PendingScript(target = target, startedAt = startedAt)
        }
    }

    fun clearPendingScript() {
        prefs().edit().remove(KEY_PENDING_TARGET).remove(KEY_PENDING_TIME).apply()
    }

    suspend fun rebootToRecovery(): Boolean = withContext(Dispatchers.IO) {
        sh("/system/bin/reboot recovery 2>/dev/null || reboot recovery").isSuccess
    }

    // ----------------------------------------------------------------- private

    private val flasherPrefs by lazy { PoxPrefs.of("flasher.json", PREFS) }

    /** Loads the config file up front, off the UI thread (called from the application class). */
    fun warmup() = flasherPrefs.ensureLoaded()

    private fun prefs() = flasherPrefs

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun dumpPartition(device: String, target: String) {
        val expected = partitionSizeBytes(device)
        sh("dd if=${q(device)} of=${q(target)} bs=4M 2>/dev/null && sync")
            .orThrow("Dumping ${device.substringAfterLast('/')} failed")
        if (expected > 0L) {
            val actual = shellFileSize(target)
                ?: throw IllegalStateException("Could not verify the dump of $device")
            if (actual != expected) {
                sh("rm -f ${q(target)}")
                throw IllegalStateException(
                    "Dump of ${device.substringAfterLast('/')} was truncated ($actual of $expected bytes)"
                )
            }
        }
    }

    private suspend fun partitionSizeBytes(device: String): Long {
        sh("blockdev --getsize64 ${q(device)} 2>/dev/null")
            .output.firstOrNull()?.trim()?.toLongOrNull()?.let { return it }
        // Fallback for builds without blockdev: sysfs size is in 512-byte sectors.
        val real = sh("readlink -f ${q(device)} 2>/dev/null").output.firstOrNull()?.trim().orEmpty()
        if (real.isEmpty()) return 0L
        val name = real.substringAfterLast('/')
        val sectors = sh("cat /sys/class/block/$name/size 2>/dev/null")
            .output.firstOrNull()?.trim()?.toLongOrNull() ?: return 0L
        return sectors * 512L
    }

    private suspend fun shellFileSize(path: String): Long? {
        sh("stat -c %s ${q(path)} 2>/dev/null").output.firstOrNull()?.trim()?.toLongOrNull()?.let { return it }
        return sh("wc -c < ${q(path)} 2>/dev/null").output.firstOrNull()?.trim()?.toLongOrNull()
    }

    private suspend fun shellSha256(path: String): String? =
        sh("sha256sum ${q(path)} 2>/dev/null").output.firstOrNull()?.trim()
            ?.substringBefore(' ')?.takeIf { it.length == 64 }

    /**
     * Copies a dump into the app cache with fixed ownership and labels.
     * Files created by the root shell in an app directory stay unreadable for
     * the app itself until this runs - without it the zip build would read
     * nothing (or garbage) back.
     */
    private suspend fun pullToCache(remotePath: String, local: File) {
        val uid = Process.myUid()
        val command = buildString {
            append("cp -f ${q(remotePath)} ${q(local.absolutePath)}")
            append(" && chown $uid:$uid ${q(local.absolutePath)}")
            append(" && chmod 600 ${q(local.absolutePath)}")
            append(" && (restorecon ${q(local.absolutePath)} >/dev/null 2>&1 || true)")
        }
        sh(command).orThrow("Could not read the backup image back")
    }

    private suspend fun writeSetInfo(
        remoteDir: String,
        setId: String,
        bootSize: Long,
        bootSha: String,
        dtboSize: Long,
        dtboSha: String?,
        zipName: String,
        downloadPath: String?
    ) {
        val lines = listOf(
            "id=$setId",
            "created=${System.currentTimeMillis() / 1000}",
            "boot.size=$bootSize",
            "boot.sha256=$bootSha",
            "dtbo.size=$dtboSize",
            "dtbo.sha256=${dtboSha.orEmpty()}",
            "zip=$zipName",
            "download=${downloadPath.orEmpty()}"
        )
        val command = "printf '%s\\n' " + lines.joinToString(" ") { q(it) } + " > ${q("$remoteDir/set.info")}"
        sh(command).orThrow("Could not write the backup description")
    }

    private suspend fun pruneBackups() {
        val ids = sh("ls -1 ${q(DE_BACKUP_ROOT)} 2>/dev/null").output
            .map { it.trim() }
            .filter { SET_ID_REGEX.matches(it) }
            .sortedDescending()
        for (id in ids.drop(KEEP_BACKUPS)) {
            sh("rm -rf ${q("$DE_BACKUP_ROOT/$id")}")
            DownloadsStore.deleteUnder("$DOWNLOAD_SUBDIR/$id")
        }
    }

    private suspend fun pruneStaged() {
        sh("ls -1t ${q(DE_FLASH_DIR)} 2>/dev/null | sed -n '${KEEP_STAGED + 1},\$p'").output
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains('/') }
            .forEach { sh("rm -f ${q("$DE_FLASH_DIR/$it")}") }
    }

    private fun sanitizeName(name: String): String {
        var cleaned = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
        if (cleaned.isBlank()) cleaned = "kernel.zip"
        if (!cleaned.endsWith(".zip", ignoreCase = true)) cleaned = "$cleaned.zip"
        return cleaned.take(96)
    }

    // -------------------------------------------------------- restore package

    private fun buildRestoreZip(
        zipFile: File,
        bootImg: File,
        dtboImg: File?,
        bootSha: String,
        dtboSha: String?,
        setId: String
    ) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/com/google/android/update-binary"))
            zip.write(restoreScript(setId, bootSha, dtboSha).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/com/google/android/updater-script"))
            zip.write("# Pox restore package: update-binary is a shell script.\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            addVerified(zip, "boot.img", bootImg, bootSha)
            if (dtboImg != null && dtboSha != null) addVerified(zip, "dtbo.img", dtboImg, dtboSha)
        }
    }

    /**
     * Streams a source image into the zip while hashing it, then compares the
     * hash with the one the partition dump reported. A mismatch means the read
     * back from the device was corrupted and the package must not ship.
     */
    private fun addVerified(zip: ZipOutputStream, entryName: String, source: File, expectedSha: String) {
        zip.putNextEntry(ZipEntry(entryName))
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha, ignoreCase = true)) {
            throw IllegalStateException("$entryName changed while building the restore package")
        }
    }

    /**
     * The shell installer inside the generated restore zip. It mirrors the
     * AnyKernel3 contract (`OUTFD=$2`, `ZIPFILE=$3`, `ui_print`), verifies the
     * extracted images against the hashes baked in below, writes both
     * partitions, then reads boot back to confirm the write landed.
     */
    private fun restoreScript(setId: String, bootSha: String, dtboSha: String?): String = buildString {
        appendLine("#!/sbin/sh")
        appendLine("#")
        appendLine("# Pox Kernel Manager restore package - backup set $setId")
        appendLine("# Writes boot.img (and dtbo.img when present) back to the device.")
        appendLine("#")
        appendLine("# boot.img sha256: $bootSha")
        if (dtboSha != null) appendLine("# dtbo.img sha256: $dtboSha")
        appendLine("#")
        appendLine("OUTFD=\$2")
        appendLine("ZIPFILE=\$3")
        appendLine("")
        appendLine("ui_print() {")
        appendLine("  echo \"ui_print \$1")
        appendLine("ui_print\" >> /proc/self/fd/\$OUTFD")
        appendLine("}")
        appendLine("")
        appendLine("ui_print \"*****************************\"")
        appendLine("ui_print \"  Pox restore set $setId\"")
        appendLine("ui_print \"*****************************\"")
        appendLine("")
        appendLine("BOOT=\"\"")
        appendLine("DTBO=\"\"")
        appendLine("for d in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/bootdevice/by-name; do")
        appendLine("  [ -z \"\$BOOT\" ] && [ -e \"\$d/boot\" ] && BOOT=\"\$d/boot\"")
        appendLine("  [ -z \"\$DTBO\" ] && [ -e \"\$d/dtbo\" ] && DTBO=\"\$d/dtbo\"")
        appendLine("done")
        appendLine("[ -n \"\$BOOT\" ] || { ui_print \"! boot partition not found\"; exit 1; }")
        appendLine("")
        appendLine("TMP=/tmp/pox_restore")
        appendLine("rm -rf \$TMP")
        appendLine("mkdir -p \$TMP")
        appendLine("")
        appendLine("ui_print \"- Extracting the backup images\"")
        appendLine("unzip -o \"\$ZIPFILE\" boot.img dtbo.img -d \$TMP > /dev/null 2>&1")
        appendLine("[ -f \$TMP/boot.img ] || { ui_print \"! boot.img is missing from the package\"; exit 1; }")
        appendLine("")
        appendLine("if command -v sha256sum >/dev/null 2>&1; then")
        appendLine("  ui_print \"- Checking the checksums\"")
        appendLine("  [ \"\$(sha256sum \$TMP/boot.img | cut -d' ' -f1)\" = \"$bootSha\" ] || { ui_print \"! boot.img checksum mismatch - aborting\"; exit 1; }")
        if (dtboSha != null) {
            appendLine("  if [ -f \$TMP/dtbo.img ]; then")
            appendLine("    [ \"\$(sha256sum \$TMP/dtbo.img | cut -d' ' -f1)\" = \"$dtboSha\" ] || { ui_print \"! dtbo.img checksum mismatch - aborting\"; exit 1; }")
            appendLine("  fi")
        }
        appendLine("fi")
        appendLine("")
        appendLine("echo \"progress 1 60\" >> /proc/self/fd/\$OUTFD")
        appendLine("ui_print \"- Writing the boot image\"")
        appendLine("dd if=\$TMP/boot.img of=\$BOOT bs=4M 2>/dev/null || { ui_print \"! boot write failed\"; exit 1; }")
        if (dtboSha != null) {
            appendLine("")
            appendLine("if [ -n \"\$DTBO\" ] && [ -f \$TMP/dtbo.img ]; then")
            appendLine("  ui_print \"- Writing the dtbo image\"")
            appendLine("  dd if=\$TMP/dtbo.img of=\$DTBO bs=4M 2>/dev/null || { ui_print \"! dtbo write failed\"; exit 1; }")
            appendLine("fi")
        }
        appendLine("sync")
        appendLine("")
        appendLine("if command -v sha256sum >/dev/null 2>&1; then")
        appendLine("  R=\$(dd if=\$BOOT bs=4M 2>/dev/null | sha256sum | cut -d' ' -f1)")
        appendLine("  if [ \"\$R\" = \"$bootSha\" ]; then")
        appendLine("    ui_print \"- boot read-back verified\"")
        appendLine("  else")
        appendLine("    ui_print \"! boot read-back differs - re-run this package if the device does not boot\"")
        appendLine("  fi")
        appendLine("fi")
        appendLine("")
        appendLine("rm -rf \$TMP")
        appendLine("ui_print \"- Restore complete - finish with Reboot -> System\"")
        appendLine("exit 0")
    }
}
