package com.pox.kernelmanager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.model.TuningFeature
import com.pox.kernelmanager.data.model.TuningFeatures
import com.pox.kernelmanager.data.model.ZramWritebackMode
import com.pox.kernelmanager.data.repo.TuningRepository
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeaturesUiState(
    val features: TuningFeatures = TuningFeatures(),
    val isRootGranted: Boolean = false,
    val isLoading: Boolean = true,
    val feedbackMessage: String? = null
)

class FeaturesViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(FeaturesUiState())
    val uiState: StateFlow<FeaturesUiState> = _uiState.asStateFlow()

    init {
        loadFeatures()
    }

    fun onAppForegrounded() {
        ShellUtils.invalidateCache()
        com.pox.kernelmanager.data.root.KernelNodes.invalidate()
        loadFeatures()
    }

    fun loadFeatures() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val isRoot = ShellUtils.isRootAvailable()
            val feat = TuningRepository.getTuningFeatures()
            _uiState.value = FeaturesUiState(
                features = feat,
                isRootGranted = isRoot,
                isLoading = false
            )
        }
    }

    /**
     * Apply a change and then re-read the kernel.
     *
     * The previous version copied the requested value into the UI state
     * optimistically, so a rejected write still rendered as if it had been
     * applied. Re-reading means the switches always show what the kernel reports.
     */
    private fun apply(block: suspend () -> com.pox.kernelmanager.data.repo.TuningApplyResult) {
        viewModelScope.launch {
            val result = block()
            val refreshed = TuningRepository.getTuningFeatures()
            _uiState.value = _uiState.value.copy(
                features = refreshed,
                feedbackMessage = result.message
            )
        }
    }

    fun setGamingMode(level: GamingModeLevel) = apply { TuningRepository.setGamingMode(level) }

fun setTouchBoost(enable: Boolean) = apply { TuningRepository.setTouchBoost(enable) }

fun setFpsCap(fps: Int) = apply { TuningRepository.setFpsCap(fps) }

fun setIoScheduler(scheduler: String) = apply { TuningRepository.setIoScheduler(scheduler) }

fun setCpuGovernor(governor: String) = apply { TuningRepository.setCpuGovernor(governor) }

fun setZramSizeMb(sizeMb: Long) = apply { TuningRepository.setZramSizeMb(sizeMb) }

fun setZramBackingEnabled(enabled: Boolean) = apply { TuningRepository.setZramBackingEnabled(enabled) }

fun setZramBackingSizeMb(sizeMb: Long) = apply { TuningRepository.setZramBackingSizeMb(sizeMb) }

fun setZramCompressor(algorithm: String) = apply { TuningRepository.setZramCompressor(algorithm) }

fun setZramWritebackLimitMb(limitMb: Long) = apply { TuningRepository.setZramWritebackLimitMb(limitMb) }

fun setZramWritebackLimitEnabled(enabled: Boolean) =
    apply { TuningRepository.setZramWritebackLimitEnabled(enabled) }

fun triggerZramWriteback(mode: ZramWritebackMode) = apply { TuningRepository.triggerZramWriteback(mode) }

    fun toggleBypassCharging(enable: Boolean) = apply { TuningRepository.setBypassCharging(enable) }

    fun setChargeLimit(limitPercent: Int) = apply { TuningRepository.setChargeLimit(limitPercent) }

    fun toggleThermalGuard(enable: Boolean, tempLimit: Int) =
        apply { TuningRepository.setThermalGuard(enable, tempLimit) }

    fun toggleXswapd(enable: Boolean) = apply { TuningRepository.setXswapdEnabled(enable) }

    fun triggerXswapdReclaim() = apply { TuningRepository.triggerXswapdReclaim() }

    fun toggleDt2w(enable: Boolean) = apply { TuningRepository.setDoubleTapToWake(enable) }

    fun toggleTrueTone(enable: Boolean) = apply { TuningRepository.setTrueTone(enable) }

    fun setVibratorStrength(percent: Int) = apply { TuningRepository.setVibratorStrength(percent) }

    fun toggleDynamicFsync(enable: Boolean) = apply { TuningRepository.setDynamicFsync(enable) }

    fun setReadAhead(kb: Int) = apply { TuningRepository.setReadAheadKb(kb) }

    fun isSupported(feature: TuningFeature): Boolean = _uiState.value.features.isSupported(feature)

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null)
    }
}