package com.pox.kernelmanager.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pox.kernelmanager.data.AppSettings
import com.pox.kernelmanager.data.model.KernelFlashOutcome
import com.pox.kernelmanager.data.model.KernelZipInfo
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Direct AnyKernel3 flashing without rebooting into recovery.
 *
 * The package's own `update-binary` is executed in a root shell. AnyKernel3
 * unpacks the live boot image, injects the new kernel/ramdisk, repacks it, and
 * writes the result back to the boot partition. This is the same mechanism
 * KernelFlasher uses; it is deliberately not a naive raw `dd` of `Image.gz-dtb`
 * because that would discard the ramdisk and the device-specific boot header.
 *
 * Safety rules:
 *  - the ZIP must declare begonia (or begoniain) in `anykernel.sh`
 *  - the current boot partition is backed up and hashed before the installer runs
 *  - a failed install, or a boot partition that did not change, triggers a restore
 */
object KernelFlashRepository {

    private val BEGONIA_NAMES = setOf("begonia", "begoniain", "begonia_in")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val DEVICE_LINE = Regex("""(?m)^\s*device\.name\d*\s*=\s*(.*?)\s*$""")
    private val KERNEL_LINE = Regex("""(?m)^\s*kernel\.string\s*=\s*(.*?)\s*$""")

    private const val UPDATE_BINARY = "META-INF/com/google/android/update-binary"
    private const val ANY_KERNEL = "anykernel.sh"

    private data class BootBackup(
        val partition: String,
        val path: String,
        val partitionHash: String
    )

    /**
     * Copy a SAF-selected ZIP into the app cache and validate it.
     *
     * The copy is intentional: the document URI can disappear or become
     * unreadable while the long-running root install is in progress.
     */
    suspend fun importFromUri(context: Context, uri: Uri): KernelZipInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "kernel.zip"
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: displayName
                }
            }
        }

        val safeName = buildString {
            append(displayName.substringAfterLast('/').substringAfterLast('\\'))
            if (!endsWith(".zip", ignoreCase = true)) append(".zip")
        }.replace(Regex("[^A-Za-z0-9._-]"), "_")

        val directory = File(context.cacheDir, "kernel-zips").apply { mkdirs() }
        val destination = File(directory, "${System.currentTimeMillis()}-$safeName")
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("The selected document could not be opened")

        if (destination.length() == 0L) {
            destination.delete()
            throw IllegalArgumentException("The selected ZIP is empty")
        }

        inspect(destination, displayName)
    }

    /** Read and validate an AnyKernel3 ZIP without modifying anything. */
    suspend fun inspect(file: File, displayName: String = file.name): KernelZipInfo =
        withContext(Dispatchers.IO) {
            if (!file.isFile) throw IllegalArgumentException("Kernel ZIP not found")

            ZipFile(file).use { zip ->
                val anyKernel = zip.getEntry(ANY_KERNEL)
                    ?: throw IllegalArgumentException("Not an AnyKernel3 ZIP: anykernel.sh is missing")
                zip.getEntry(UPDATE_BINARY)
                    ?: throw IllegalArgumentException("Not a flashable AnyKernel3 ZIP: update-binary is missing")
                if (findBusybox(zip) == null) {
                    throw IllegalArgumentException("The package has no tools/busybox; it cannot run its installer")
                }

                val properties = zip.getInputStream(anyKernel).bufferedReader().use { it.readText() }
                val devices = DEVICE_LINE.findAll(properties)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toList()

                if (devices.isNotEmpty() && devices.none { it.lowercase(Locale.US) in BEGONIA_NAMES }) {
                    throw IllegalArgumentException(
                        "This ZIP targets ${devices.joinToString(", ")}; this app only flashes begonia."
                    )
                }

                val kernelString = KERNEL_LINE.find(properties)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                KernelZipInfo(
                    file = file,
                    displayName = displayName,
                    sizeBytes = file.length(),
                    kernelString = kernelString,
                    devices = devices
                )
            }
        }

    /**
     * Flash [zipFile] to the active boot partition.
     *
     * [onStep] receives short progress messages suitable for a status line.
     * [onLog] receives every raw installer line. Both callbacks are invoked from
     * the IO dispatcher.
     */
    suspend fun flashZip(
        context: Context,
        zipFile: File,
        onStep: (String) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): KernelFlashOutcome = withContext(Dispatchers.IO) {
        var workDirectory: File? = null
        var backup: BootBackup? = null

        try {
            val root = ShellUtils.environment()
            if (!root.granted) {
                return@withContext KernelFlashOutcome.Failure(
                    "Root access is required to write the boot partition. Grant superuser access and try again."
                )
            }

            val packageInfo = inspect(zipFile)
            onStep("Preparing ${packageInfo.displayName}...")
            onLog("Package: ${packageInfo.displayName}")
            packageInfo.kernelString?.let { onLog("Kernel: $it") }
            if (packageInfo.devices.isEmpty()) {
                onLog("Warning: this package does not declare a device name.")
            }

            val work = File(context.filesDir, "kernel-flash")
            workDirectory = work
            if (work.exists()) work.deleteRecursively()
            if (!work.mkdirs()) throw IllegalStateException("Could not create the flash workspace")

            val payload = File(work, "payload.zip")
            zipFile.copyTo(payload, overwrite = true)

            val updateBinary = File(work, "update-binary")
            val busybox = File(work, "busybox")
            ZipFile(payload).use { zip ->
                val updateEntry = zip.getEntry(UPDATE_BINARY)
                    ?: throw IllegalStateException("update-binary disappeared from the package")
                val busyboxEntry = findBusybox(zip)
                    ?: throw IllegalStateException("busybox disappeared from the package")
                extract(zip, updateEntry, updateBinary)
                extract(zip, busyboxEntry, busybox)
            }

            val script = File(work, "flash_ak3.sh")
            context.assets.open("flash_ak3.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }

            onStep("Backing up the current boot image...")
            backup = backupBoot(onLog)
            AppSettings.setLastBootBackup(backup.path)
            onLog("Boot backup: ${backup.path}")

            onStep("Running the AnyKernel3 installer...")
            val command = buildString {
                append("F=").append(shellQuote(work.absolutePath))
                append(" Z=").append(shellQuote(payload.absolutePath))
                append(" /system/bin/sh ").append(shellQuote(script.absolutePath))
            }
            val result = ShellUtils.runCommandStreaming(command) { line ->
                onLog(line)
                uiMessage(line)?.let(onStep)
            }

            if (!result.isSuccess) {
                return@withContext restoreAfterFailure(
                    backup = backup,
                    reason = "The installer exited with code ${result.exitCode}.",
                    onStep = onStep,
                    onLog = onLog
                )
            }

            onStep("Verifying the boot partition...")
            val afterHash = sha256(backup.partition)
            if (afterHash == null) {
                return@withContext restoreAfterFailure(
                    backup = backup,
                    reason = "The boot partition could not be read back for verification.",
                    onStep = onStep,
                    onLog = onLog
                )
            }
            if (afterHash.equals(backup.partitionHash, ignoreCase = true)) {
                return@withContext restoreAfterFailure(
                    backup = backup,
                    reason = "The boot partition did not change; the package was not applied.",
                    onStep = onStep,
                    onLog = onLog
                )
            }

            onStep("Kernel flashed successfully.")
            KernelFlashOutcome.Success(
                message = "Kernel flashed. Reboot to apply it.",
                backupPath = backup.path
            )
        } catch (e: Exception) {
            if (backup != null) {
                restoreAfterFailure(
                    backup = backup,
                    reason = e.message ?: "The flash failed unexpectedly.",
                    onStep = onStep,
                    onLog = onLog
                )
            } else {
                KernelFlashOutcome.Failure(e.message ?: "The flash failed unexpectedly.")
            }
        } finally {
            workDirectory?.deleteRecursively()
        }
    }

    /** Restore the boot image saved before the most recent flash. */
    suspend fun restoreLastBackup(
        onStep: (String) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): KernelFlashOutcome = withContext(Dispatchers.IO) {
        val backupPath = AppSettings.lastBootBackup.value
            ?: return@withContext KernelFlashOutcome.Failure("No boot backup has been recorded yet.")

        if (!File(backupPath).isFile) {
            return@withContext KernelFlashOutcome.Failure(
                "The recorded boot backup is missing: $backupPath"
            )
        }

        val partition = resolveBootPartition()
            ?: return@withContext KernelFlashOutcome.Failure("Could not find the active boot partition.")

        val backupHash = sha256(backupPath)
            ?: return@withContext KernelFlashOutcome.Failure("Could not hash the boot backup.")

        onStep("Restoring the boot backup...")
        val restore = ShellUtils.runCommand(
            "dd if=${shellQuote(backupPath)} of=${shellQuote(partition)} bs=4M 2>&1 && sync"
        )
        restore.output.forEach(onLog)
        if (!restore.isSuccess) {
            return@withContext KernelFlashOutcome.Failure(
                "Restoring the boot backup failed.",
                bootRestored = false
            )
        }

        val restoredHash = sha256(partition)
        val restored = restoredHash != null && restoredHash.equals(backupHash, ignoreCase = true)
        if (restored) {
            onStep("Boot backup restored.")
            KernelFlashOutcome.Success("Boot backup restored. Reboot to return to the previous kernel.", backupPath)
        } else {
            KernelFlashOutcome.Failure(
                "The backup was written, but the boot partition did not match it afterward.",
                bootRestored = false
            )
        }
    }

    private suspend fun backupBoot(onLog: (String) -> Unit): BootBackup {
        val partition = resolveBootPartition()
            ?: throw IllegalStateException("Could not find the active boot partition.")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val path = "/data/local/tmp/pox-boot-$stamp.img"
        val command = "mkdir -p /data/local/tmp && " +
            "dd if=${shellQuote(partition)} of=${shellQuote(path)} bs=4M 2>&1 && " +
            "sync && test -s ${shellQuote(path)}"
        val result = ShellUtils.runCommand(command)
        result.output.forEach(onLog)
        if (!result.isSuccess) {
            throw IllegalStateException("Could not back up $partition.")
        }

        val partitionHash = sha256(partition)
            ?: throw IllegalStateException("Could not hash the current boot partition.")
        val backupHash = sha256(path)
            ?: throw IllegalStateException("Could not hash the boot backup.")
        if (!partitionHash.equals(backupHash, ignoreCase = true)) {
            throw IllegalStateException("The boot backup did not match the live partition; refusing to flash.")
        }

        return BootBackup(partition, path, partitionHash)
    }

    private suspend fun restoreAfterFailure(
        backup: BootBackup,
        reason: String,
        onStep: (String) -> Unit,
        onLog: (String) -> Unit
    ): KernelFlashOutcome {
        onStep("Flash failed. Restoring the boot backup...")
        onLog(reason)

        val restore = ShellUtils.runCommand(
            "dd if=${shellQuote(backup.path)} of=${shellQuote(backup.partition)} bs=4M 2>&1 && sync"
        )
        restore.output.forEach(onLog)
        val restoredHash = if (restore.isSuccess) sha256(backup.partition) else null
        val restored = restoredHash != null && restoredHash.equals(backup.partitionHash, ignoreCase = true)

        return if (restored) {
            KernelFlashOutcome.Failure(
                "$reason The previous boot image was restored.",
                bootRestored = true
            )
        } else {
            KernelFlashOutcome.Failure(
                "$reason CRITICAL: the boot backup could not be restored. " +
                    "Use recovery or fastboot to restore ${backup.path}.",
                bootRestored = false
            )
        }
    }

    private suspend fun resolveBootPartition(): String? {
        val slot = ShellUtils.runCommand("getprop ro.boot.slot_suffix")
            .output
            .firstOrNull()
            ?.trim()
            .orEmpty()

        val candidates = listOf(
            "/dev/block/by-name/boot$slot",
            "/dev/block/bootdevice/by-name/boot$slot",
            "/dev/block/platform/bootdevice/by-name/boot$slot"
        )
        for (candidate in candidates) {
            val exists = ShellUtils.runCommand("test -e ${shellQuote(candidate)}")
            if (exists.isSuccess) return candidate
        }

        return ShellUtils.runCommand(
            "find /dev/block -maxdepth 4 -type l -name 'boot$slot' 2>/dev/null | head -n 1"
        ).output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun sha256(path: String): String? {
        val result = ShellUtils.runCommand(
            "sha256sum ${shellQuote(path)} 2>/dev/null || toybox sha256sum ${shellQuote(path)} 2>/dev/null"
        )
        val hash = result.output.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
        return hash?.takeIf { SHA256.matches(it) }
    }

    private fun findBusybox(zip: ZipFile): ZipEntry? {
        for (name in listOf(
            "tools/busybox",
            "tools/arm64-v8a/busybox",
            "tools/armeabi-v7a/busybox",
            "tools/arm/busybox"
        )) {
            zip.getEntry(name)?.let { return it }
        }
        return zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.startsWith("tools/") && it.name.endsWith("/busybox")
        }
    }

    private fun extract(zip: ZipFile, entry: ZipEntry, destination: File) {
        zip.getInputStream(entry).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun uiMessage(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("ui_print ")) return null
        return trimmed.removePrefix("ui_print ").trim().takeIf { it.isNotEmpty() }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
