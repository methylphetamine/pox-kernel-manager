package com.pox.kernelmanager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.model.GithubRelease
import com.pox.kernelmanager.data.model.KernelPackageItem
import com.pox.kernelmanager.data.model.UpdateState
import com.pox.kernelmanager.data.repo.DownloadsStore
import com.pox.kernelmanager.data.repo.HardwareRepository
import com.pox.kernelmanager.data.repo.KernelUpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class UpdaterViewModel : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _availablePackages = MutableStateFlow<List<KernelPackageItem>>(emptyList())
    val availablePackages: StateFlow<List<KernelPackageItem>> = _availablePackages.asStateFlow()

    private val _selectedPackage = MutableStateFlow<KernelPackageItem?>(null)
    val selectedPackage: StateFlow<KernelPackageItem?> = _selectedPackage.asStateFlow()

    private val _latestRelease = MutableStateFlow<GithubRelease?>(null)
    val latestRelease: StateFlow<GithubRelease?> = _latestRelease.asStateFlow()

    init {
        checkForUpdates()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val deviceInfo = HardwareRepository.getDeviceInfo()
                val releases = KernelUpdateRepository.fetchLatestReleases()
                val latest = releases.firstOrNull()

                if (latest != null) {
                    _latestRelease.value = latest
                    val packages = KernelUpdateRepository.parsePackagesFromRelease(latest, deviceInfo.kernelEdition)
                    _availablePackages.value = packages

                    // Default select recommended package matching current edition
                    val recommended = packages.firstOrNull { it.isRecommendedForDevice } ?: packages.firstOrNull()
                    _selectedPackage.value = recommended

                    val localRelease = deviceInfo.kernelRelease
                    val isNewer = !localRelease.contains(latest.tag_name, ignoreCase = true)

                    _updateState.value = if (isNewer || packages.isNotEmpty()) {
                        UpdateState.UpdateAvailable(latest, packages)
                    } else {
                        UpdateState.UpToDate
                    }
                } else {
                    _updateState.value = UpdateState.Error("No releases found on GitHub")
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Failed to check updates")
            }
        }
    }

    fun selectPackage(item: KernelPackageItem) {
        _selectedPackage.value = item
    }

    fun downloadSelectedPackage() {
        val item = _selectedPackage.value ?: return
        viewModelScope.launch {
            try {
                // Stream into app cache first: it is always writable, and the
                // integrity check must run before anything reaches shared storage.
                val cacheFile = File(PoxApplication.instance.cacheDir, item.zipName)

                KernelUpdateRepository.downloadPackageWithProgress(item.downloadUrl, cacheFile)
                    .collect { progress ->
                        _updateState.value = UpdateState.Downloading(
                            packageName = item.zipName,
                            progressPercent = progress.percent,
                            downloadedMb = progress.downloadedMb,
                            totalMb = progress.totalMb,
                            speedKbps = progress.speedKbps
                        )
                    }

                // Verify SHA-256
                val isVerified = KernelUpdateRepository.verifySha256(cacheFile, item.sha256)
                if (!isVerified && item.sha256.isNotEmpty()) {
                    cacheFile.delete()
                    _updateState.value = UpdateState.Error("SHA-256 integrity check failed!")
                    return@launch
                }

                // Publish to Downloads via MediaStore. Writing straight to
                // Environment.getExternalStoragePublicDirectory() throws under
                // scoped storage on API 29+.
                val location = DownloadsStore.write(
                    fileName = item.zipName,
                    mimeType = "application/zip",
                    content = cacheFile.readBytes()
                )

                _updateState.value = UpdateState.DownloadCompleted(cacheFile, location)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Download error: ${e.message}")
            }
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}
