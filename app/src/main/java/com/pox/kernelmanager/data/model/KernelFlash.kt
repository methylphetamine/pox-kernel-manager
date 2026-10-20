package com.pox.kernelmanager.data.model

import java.io.File

/**
 * A validated AnyKernel3 package selected for direct flashing.
 *
 * [devices] holds the `device.name*` entries declared by `anykernel.sh`. An
 * empty list means the package does not declare a device check; the installer
 * will still run, but the flasher shows that fact before writing anything.
 */
data class KernelZipInfo(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val kernelString: String?,
    val devices: List<String>
)

/**
 * UI state for the direct kernel ZIP flasher.
 *
 * The log is deliberately capped by the ViewModel. AnyKernel3 can print many
 * hundreds of lines, and keeping all of them in a StateFlow only makes Compose
 * recompose more often.
 */
data class KernelFlashUiState(
    val rootGranted: Boolean = false,
    val rootLabel: String = "Checking root...",
    val zip: KernelZipInfo? = null,
    val isImporting: Boolean = false,
    val isFlashing: Boolean = false,
    val currentStep: String = "",
    val log: List<String> = emptyList(),
    val outcome: KernelFlashOutcome? = null,
    val lastBackupPath: String? = null
)

sealed interface KernelFlashOutcome {
    data class Success(
        val message: String,
        val backupPath: String?
    ) : KernelFlashOutcome

    data class Failure(
        val message: String,
        /** null means no backup had been made yet, true/false means restore was attempted. */
        val bootRestored: Boolean? = null
    ) : KernelFlashOutcome
}
