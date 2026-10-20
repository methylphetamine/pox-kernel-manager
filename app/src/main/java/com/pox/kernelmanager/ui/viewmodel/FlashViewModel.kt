package com.pox.kernelmanager.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.AppSettings
import com.pox.kernelmanager.data.model.KernelFlashOutcome
import com.pox.kernelmanager.data.model.KernelFlashUiState
import com.pox.kernelmanager.data.repo.KernelFlashRepository
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FlashViewModel : ViewModel() {

    private val _state = MutableStateFlow(KernelFlashUiState())
    val state: StateFlow<KernelFlashUiState> = _state.asStateFlow()

    init {
        refreshRoot()
        viewModelScope.launch {
            AppSettings.lastBootBackup.collect { path ->
                _state.update { it.copy(lastBackupPath = path) }
            }
        }
    }

    fun refreshRoot() {
        viewModelScope.launch {
            val environment = runCatching { ShellUtils.environment() }.getOrNull()
            _state.update {
                it.copy(
                    rootGranted = environment?.granted == true,
                    rootLabel = environment?.provider?.displayName ?: "Unknown"
                )
            }
        }
    }

    fun selectZip(uri: Uri) {
        if (_state.value.isFlashing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isImporting = true,
                    outcome = null,
                    currentStep = "Reading the selected package..."
                )
            }
            try {
                val info = KernelFlashRepository.importFromUri(PoxApplication.instance, uri)
                _state.update {
                    it.copy(
                        zip = info,
                        isImporting = false,
                        currentStep = "Ready to flash",
                        log = emptyList(),
                        outcome = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isImporting = false,
                        currentStep = "",
                        outcome = KernelFlashOutcome.Failure(
                            e.message ?: "The selected ZIP could not be opened."
                        )
                    )
                }
            }
        }
    }

    fun flash() {
        val zip = _state.value.zip ?: return
        if (_state.value.isFlashing || _state.value.isImporting) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isFlashing = true,
                    currentStep = "Starting the installer...",
                    log = emptyList(),
                    outcome = null
                )
            }

            val outcome = KernelFlashRepository.flashZip(
                context = PoxApplication.instance,
                zipFile = zip.file,
                onStep = { step ->
                    _state.update { it.copy(currentStep = step) }
                },
                onLog = ::appendLog
            )

            _state.update {
                it.copy(
                    isFlashing = false,
                    outcome = outcome,
                    currentStep = when (outcome) {
                        is KernelFlashOutcome.Success -> "Kernel flashed"
                        is KernelFlashOutcome.Failure -> "Flash failed"
                    }
                )
            }
        }
    }

    fun restoreLastBackup() {
        if (_state.value.isFlashing || _state.value.isImporting) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isFlashing = true,
                    currentStep = "Restoring the boot backup...",
                    log = emptyList(),
                    outcome = null
                )
            }

            val outcome = KernelFlashRepository.restoreLastBackup(
                onStep = { step ->
                    _state.update { it.copy(currentStep = step) }
                },
                onLog = ::appendLog
            )

            _state.update {
                it.copy(
                    isFlashing = false,
                    outcome = outcome,
                    currentStep = when (outcome) {
                        is KernelFlashOutcome.Success -> "Backup restored"
                        is KernelFlashOutcome.Failure -> "Restore failed"
                    }
                )
            }
        }
    }

    fun reboot() {
        viewModelScope.launch {
            _state.update { it.copy(currentStep = "Rebooting...") }
            ShellUtils.runCommand("reboot")
        }
    }

    private fun appendLog(raw: String) {
        val line = cleanLine(raw) ?: return
        _state.update { current ->
            current.copy(log = (current.log + line).takeLast(600))
        }
    }

    private fun cleanLine(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("progress ")) return null
        if (trimmed.startsWith("ui_print")) {
            return trimmed.removePrefix("ui_print").trim().ifEmpty { null }
        }
        return trimmed
    }
}
