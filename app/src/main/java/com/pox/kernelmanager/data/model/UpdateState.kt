package com.pox.kernelmanager.data.model

import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpdateAvailable(val release: GithubRelease, val packages: List<KernelPackageItem>) : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(
        val packageName: String,
        val progressPercent: Int,
        val downloadedMb: Float,
        val totalMb: Float,
        val speedKbps: Float
    ) : UpdateState
    /** [file] stays in app cache for flashing; [savedLocation] is where it was published. */
data class DownloadCompleted(val file: File, val savedLocation: String) : UpdateState
    data class Flashing(val currentStep: String) : UpdateState
    data class FlashSuccess(
        val message: String,
        /** True when the boot partition was written directly, false when staged for recovery. */
        val direct: Boolean = true
    ) : UpdateState
    /**
     * [file] is kept when a verified repository download failed to install, so
     * the user can still stage the same package for recovery instead of
     * downloading it again.
     */
    data class Error(val message: String, val file: File? = null) : UpdateState
}
