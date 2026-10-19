package com.pox.kernelmanager.ui.viewmodel

import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.repo.BootBackupSet
import com.pox.kernelmanager.data.repo.KernelFlasher
import com.pox.kernelmanager.data.repo.KernelZipInfo
import com.pox.kernelmanager.data.repo.PendingScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The single in-progress / last-result state of the flasher. */
sealed interface FlashTask {
    data object Idle : FlashTask
    data class Working(val step: String) : FlashTask
    data class FlashReady(val packageName: String, val backup: BootBackupSet?) : FlashTask
    data class BackupDone(val backup: BootBackupSet) : FlashTask
    data class RestoreReady(val backup: BootBackupSet) : FlashTask
    data class Error(val message: String) : FlashTask
}

/**
 * Drives the Kernel Flasher section.
 *
 * Every entry point funnels into [KernelFlasher], which owns the root side:
 * the backup set is always taken inside the staging pipeline, so a flash can
 * never start without one.
 */
class FlashViewModel : ViewModel() {

    private val _task = MutableStateFlow<FlashTask>(FlashTask.Idle)
    val task: StateFlow<FlashTask> = _task.asStateFlow()

    private val _selected = MutableStateFlow<KernelZipInfo?>(null)
    val selected: StateFlow<KernelZipInfo?> = _selected.asStateFlow()

    private val _backups = MutableStateFlow<List<BootBackupSet>>(emptyList())
    val backups: StateFlow<List<BootBackupSet>> = _backups.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingNotice = MutableStateFlow<PendingScript?>(null)

    /**
     * Set when a queued recovery script survived at least one full boot: the
     * recovery never read it. Null while the script is simply waiting for the
     * user to reboot to recovery.
     */
    val pendingNotice: StateFlow<PendingScript?> = _pendingNotice.asStateFlow()

    init {
        refresh()
    }

    /** Re-read backups and check the queued-script status. */
    fun refresh() {
        refreshBackups()
        viewModelScope.launch {
            val pending = runCatching { KernelFlasher.pendingScript() }.getOrNull()
            // A queued script is only worth complaining about once a boot has
            // happened since it was written - before that it is just waiting.
            val bootInstant = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            _pendingNotice.value = pending?.takeIf { it.startedAt < bootInstant - 5_000L }
        }
    }

    private fun refreshBackups() {
        viewModelScope.launch {
            _backups.value = runCatching { KernelFlasher.listBackups() }.getOrDefault(emptyList())
        }
    }

    /** Copies a SAF-picked zip into the app cache, then inspects it. */
    fun pickZip(uri: Uri) {
        runTask {
            val resolver = PoxApplication.instance.contentResolver
            _task.value = FlashTask.Working("Reading the selected package")

            var name = "kernel.zip"
            var size = 0L
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }

            val cacheDir = File(PoxApplication.instance.cacheDir, "picked").apply { mkdirs() }
            val target = File(cacheDir, "${System.currentTimeMillis()}-${name.substringAfterLast('/')}")

            withContext(Dispatchers.IO) {
                var lastEmit = 0L
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val now = SystemClock.elapsedRealtime()
                            if (size > 0 && now - lastEmit > 250L) {
                                lastEmit = now
                                val percent = ((copied * 100) / size).toInt().coerceIn(0, 100)
                                _task.value = FlashTask.Working("Copying $name ($percent%)")
                            }
                        }
                    }
                } ?: throw IllegalStateException("Could not open the selected file")
            }

            _selected.value = KernelFlasher.inspect(target, name)
            _task.value = FlashTask.Idle
        }
    }

    /** One-tap path from the updater: inspect the downloaded zip, then flash it. */
    fun flashCachedZip(file: File) {
        runTask {
            _task.value = FlashTask.Working("Reading ${file.name}")
            _selected.value = KernelFlasher.inspect(file, file.name)
            prepareFlash()
        }
    }

    /** Stage the selected package (with the mandatory backup first). */
    fun flash() {
        if (_selected.value == null) return
        runTask { prepareFlash() }
    }

    private suspend fun prepareFlash() {
        val info = _selected.value ?: return
        val staged = KernelFlasher.stageKernelZip(info.file, info.displayName) { step ->
            _task.value = FlashTask.Working(step)
        }
        refreshBackups()
        _task.value = FlashTask.FlashReady(staged.name, staged.backup)
    }

    /** Standalone backup, outside any flash. */
    fun createBackupNow() {
        runTask {
            val backup = KernelFlasher.createBackup { step -> _task.value = FlashTask.Working(step) }
            refreshBackups()
            _task.value = FlashTask.BackupDone(backup)
        }
    }

    /** Queue a PoxRestore package: boot + dtbo are written back on the next recovery boot. */
    fun restore(backup: BootBackupSet) {
        runTask {
            KernelFlasher.stageRestore(backup) { step -> _task.value = FlashTask.Working(step) }
            _task.value = FlashTask.RestoreReady(backup)
        }
    }

    fun deleteBackup(backup: BootBackupSet) {
        viewModelScope.launch {
            runCatching { KernelFlasher.deleteBackup(backup) }
            refreshBackups()
        }
    }

    fun rebootToRecovery() {
        viewModelScope.launch { KernelFlasher.rebootToRecovery() }
    }

    fun clearSelection() {
        if (!_busy.value) _selected.value = null
    }

    fun dismissTask() {
        if (_task.value !is FlashTask.Working) _task.value = FlashTask.Idle
    }

    fun dismissPendingNotice() {
        KernelFlasher.clearPendingScript()
        _pendingNotice.value = null
    }

    private fun runTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                _task.value = FlashTask.Error(e.message ?: "The operation failed")
            } finally {
                _busy.value = false
            }
        }
    }
}
