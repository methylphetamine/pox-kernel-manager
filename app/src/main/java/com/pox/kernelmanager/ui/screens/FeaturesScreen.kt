package com.pox.kernelmanager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pox.kernelmanager.data.model.TuningFeature
import com.pox.kernelmanager.data.model.TuningFeatures
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.model.ZramWritebackMode
import com.pox.kernelmanager.ui.components.*
import com.pox.kernelmanager.ui.viewmodel.FeaturesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen(
    viewModel: FeaturesViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Root can be granted, or a module installed, while the app is backgrounded,
    // so re-probe on resume or the stale result leaves everything greyed out.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppForegrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        uiState.feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        topBar = {
            PoxTopAppBar(
                title = "Kernel Tuning",
                onRefresh = { viewModel.loadFeatures() },
                isRefreshing = uiState.isLoading
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // 1. Gaming Mode & KProfiles
            SectionHeader(
                title = "Gaming & Performance Governor",
                subtitle = "MediaTek perfmgr EAS boost + FPSGO touch boost"
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Active Performance Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        GamingModeLevel.entries.forEachIndexed { index, level ->
                            SegmentedButton(
                                selected = uiState.features.gamingMode == level,
                                onClick = { viewModel.setGamingMode(level) },
                                enabled = uiState.features.isSupported(TuningFeature.GAMING_MODE),
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = GamingModeLevel.entries.size)
                            ) {
                                Text(
                                    text = when (level) {
                                        GamingModeLevel.BALANCED -> "Balanced"
                                        GamingModeLevel.GAMING -> "Gaming"
                                        GamingModeLevel.EXTREME -> "Extreme"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (uiState.features.isSupported(TuningFeature.GAMING_MODE)) {
                            uiState.features.gamingMode.description
                        } else {
                            "/proc/perfmgr is unavailable - grant root to enable"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FeatureSwitchCard(
                title = "FPSGO Touch Boost",
                description = "Raises the target OPP on touch via /proc/perfmgr/tchbst/user/usrtch",
                isChecked = uiState.features.touchBoost240Hz,
                onCheckedChange = { viewModel.setTouchBoost(it) },
                icon = Icons.Default.TouchApp,
                enabled = uiState.features.isSupported(TuningFeature.TOUCH_BOOST)
            )

            // 2. Battery & Direct Bypass
            SectionHeader(
                title = "Smart Battery & Bypass Charging",
                subtitle = "Not implemented in pox-begonia-kernel"
            )

            FeatureSwitchCard(
                title = "Direct-Power Bypass Mode",
                description = "Power phone directly via charger port, halting battery heating and degradation during gaming or heavy workloads",
                isChecked = uiState.features.bypassCharging,
                onCheckedChange = { viewModel.toggleBypassCharging(it) },
                icon = Icons.Default.ElectricBolt,
                enabled = uiState.features.isSupported(TuningFeature.BYPASS_CHARGING)
            )

            FeatureSliderCard(
                title = "Charging Stop Limit",
                valueLabel = "${uiState.features.chargeLimitPercent}%",
                description = "Stops charging when battery reaches this threshold to prolong lifespan",
                value = uiState.features.chargeLimitPercent.toFloat(),
                valueRange = 60f..100f,
                steps = 7,
                onValueChange = { viewModel.setChargeLimit(it.toInt()) },
                enabled = uiState.features.isSupported(TuningFeature.CHARGE_LIMIT)
            )

            FeatureSwitchCard(
                title = "Hardware Thermal Guard",
                description = "Clamps charging current when battery temperature exceeds ${uiState.features.tempLimitC}°C",
                isChecked = uiState.features.thermalGuardEnabled,
                onCheckedChange = { viewModel.toggleThermalGuard(it, uiState.features.tempLimitC) },
                icon = Icons.Default.Thermostat,
                enabled = uiState.features.isSupported(TuningFeature.THERMAL_GUARD)
            )

            UnsupportedReason(uiState.features.reasonFor(TuningFeature.THERMAL_GUARD))

            // 3. MIUI / HyperOS xswapd Subsystem
            SectionHeader(
                title = "HyperOS xswapd Daemon",
                subtitle = "Asynchronous per-memcg page swap & memory compressor"
            )

            FeatureSwitchCard(
                title = "xswapd Background Engine",
                description = "Offloads memory swap compression to unbound kernel workers, preventing binder stalls and launcher redraws",
                isChecked = uiState.features.xswapdEnabled,
                onCheckedChange = { viewModel.toggleXswapd(it) },
                icon = Icons.Default.Compress,
                enabled = uiState.features.isSupported(TuningFeature.XSWAPD)
            )

            UnsupportedReason(uiState.features.reasonFor(TuningFeature.XSWAPD))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force Memory Reclaim",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Triggers an immediate 100MB xswapd reclaim pass across all apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledTonalButton(
                        onClick = { viewModel.triggerXswapdReclaim() },
                        enabled = uiState.features.isSupported(TuningFeature.XSWAPD),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reclaim")
                    }
                }
            }

            // 4. Display, Touch & Vibrator
            SectionHeader(
                title = "Display, Touch & Haptics",
                subtitle = "Not implemented in pox-begonia-kernel"
            )

            FeatureSwitchCard(
                title = "Double Tap to Wake (DT2W)",
                description = "Wake screen by double tapping touch panel in deep sleep",
                isChecked = uiState.features.doubleTapToWake,
                onCheckedChange = { viewModel.toggleDt2w(it) },
                icon = Icons.Default.TouchApp,
                enabled = uiState.features.isSupported(TuningFeature.DT2W)
            )

            FeatureSwitchCard(
                title = "True Tone Liquid Retina D65",
                description = "Calibrated D65 color balance reference profile for begonia LCD",
                isChecked = uiState.features.trueToneDisplay,
                onCheckedChange = { viewModel.toggleTrueTone(it) },
                icon = Icons.Default.Palette,
                enabled = uiState.features.isSupported(TuningFeature.TRUE_TONE)
            )

            FeatureSliderCard(
                title = "Hardware Vibrator Strength",
                valueLabel = "${uiState.features.vibratorStrengthPercent}%",
                description = "Unlocked 3.3V analog haptic motor voltage driving range",
                value = uiState.features.vibratorStrengthPercent.toFloat(),
                valueRange = 0f..100f,
                steps = 19,
                onValueChange = { viewModel.setVibratorStrength(it.toInt()) },
                enabled = uiState.features.isSupported(TuningFeature.VIBRATOR_STRENGTH)
            )

            // 5. Storage, I/O scheduler & ZRAM
            SectionHeader(
                title = "Storage, I/O Scheduler & ZRAM",
                subtitle = "UFS queue tuning, compressed swap and disk writeback"
            )

            FeatureSwitchCard(
                title = "Dynamic Fsync",
                description = "Disables synchronous flush when screen is on, flushing immediately when screen goes off",
                isChecked = uiState.features.dynamicFsync,
                onCheckedChange = { viewModel.toggleDynamicFsync(it) },
                icon = Icons.Default.Storage,
                enabled = uiState.features.isSupported(TuningFeature.DYNAMIC_FSYNC)
            )

            UnsupportedReason(uiState.features.reasonFor(TuningFeature.DYNAMIC_FSYNC))

            FeatureSliderCard(
                title = "UFS Queue Readahead",
                valueLabel = "${uiState.features.readAheadKb} KB",
                description = "Readahead buffer size for sequential flash throughput",
                value = uiState.features.readAheadKb.toFloat(),
                valueRange = 64f..512f,
                steps = 6,
                onValueChange = { viewModel.setReadAhead(it.toInt()) },
                enabled = uiState.features.isSupported(TuningFeature.READ_AHEAD)
            )
            UnsupportedReason(uiState.features.reasonFor(TuningFeature.READ_AHEAD))

            if (uiState.features.availableSchedulers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buildString {
                        append("I/O SCHEDULER")
                        if (uiState.features.schedulerQueue.isNotBlank()) {
                            append(" - ")
                            append(uiState.features.schedulerQueue)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.features.availableSchedulers.forEach { scheduler ->
                        FilterChip(
                            selected = uiState.features.ioScheduler == scheduler,
                            onClick = { viewModel.setIoScheduler(scheduler) },
                            enabled = uiState.features.isSupported(TuningFeature.IO_SCHEDULER),
                            label = {
                                Text(scheduler, style = MaterialTheme.typography.labelSmall)
                            }
                        )
                    }
                }
                UnsupportedReason(uiState.features.reasonFor(TuningFeature.IO_SCHEDULER))
            }

            if (uiState.features.availableGovernors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "CPU GOVERNOR",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp)) {
                    uiState.features.availableGovernors.forEachIndexed { index, governor ->
                        SegmentedButton(
                            selected = uiState.features.cpuGovernor == governor,
                            onClick = { viewModel.setCpuGovernor(governor) },
                            enabled = uiState.features.isSupported(TuningFeature.CPU_GOVERNOR),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = uiState.features.availableGovernors.size
                            )
                        ) {
                            Text(governor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                UnsupportedReason(uiState.features.reasonFor(TuningFeature.CPU_GOVERNOR))
            }

            ZramControlCard(
                features = uiState.features,
                onSizeSelected = { viewModel.setZramSizeMb(it) },
                onBackingToggled = { viewModel.setZramBackingEnabled(it) },
                onBackingSizeSelected = { viewModel.setZramBackingSizeMb(it) },
                onCompressorSelected = { viewModel.setZramCompressor(it) },
                onWritebackLimitSelected = { viewModel.setZramWritebackLimitMb(it) },
                onWritebackLimitToggled = { viewModel.setZramWritebackLimitEnabled(it) },
                onWriteback = { viewModel.triggerZramWriteback(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ZramControlCard(
    features: TuningFeatures,
    onSizeSelected: (Long) -> Unit,
    onBackingToggled: (Boolean) -> Unit,
    onBackingSizeSelected: (Long) -> Unit,
    onCompressorSelected: (String) -> Unit,
    onWritebackLimitSelected: (Long) -> Unit,
    onWritebackLimitToggled: (Boolean) -> Unit,
    onWriteback: (ZramWritebackMode) -> Unit
) {
    val sizeSupported = features.isSupported(TuningFeature.ZRAM_SIZE)
    val backingSupported = features.isSupported(TuningFeature.ZRAM_BACKING)
    val writebackSupported = features.isSupported(TuningFeature.ZRAM_WRITEBACK)
    val compressorSupported = features.isSupported(TuningFeature.ZRAM_COMPRESSOR)

    val sizeOptions = listOf(128L, 256L, 512L, 1024L, 2048L, 3072L).let { defaults ->
        if (features.zramSizeMb > 0 && features.zramSizeMb !in defaults) {
            (defaults + features.zramSizeMb).sorted()
        } else defaults
    }
    val backingSizeOptions = listOf(256L, 512L, 1024L, 2048L, 4096L).let { defaults ->
        if (features.zramBackingSizeMb > 0 && features.zramBackingSizeMb !in defaults) {
            (defaults + features.zramBackingSizeMb).sorted()
        } else defaults
    }
    val limitOptions = listOf(128L, 256L, 512L, 1024L, 2048L).let { defaults ->
        if (features.zramWritebackLimitMb > 0 && features.zramWritebackLimitMb !in defaults) {
            (defaults + features.zramWritebackLimitMb).sorted()
        } else defaults
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ZRAM compressed swap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (features.zramSizeMb > 0) {
                            buildString {
                                append(formatZramMb(features.zramSizeMb))
                                if (features.zramCompAlgorithm.isNotBlank()) {
                                    append(" • ")
                                    append(features.zramCompAlgorithm)
                                }
                                if (features.zramBackingEnabled) append(" • writeback")
                            }
                        } else {
                            "Not configured"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                StatusBadge(
                    text = if (features.zramSizeMb > 0) "Active" else "Off",
                    isActive = features.zramSizeMb > 0
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "SWAP SIZE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            ChipRow(
                options = sizeOptions,
                selected = features.zramSizeMb,
                enabled = sizeSupported,
                label = { formatZramMb(it) },
                onSelected = onSizeSelected
            )

            if (!sizeSupported) {
                UnsupportedReason(features.reasonFor(TuningFeature.ZRAM_SIZE))
            } else {
                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Disk writeback backing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Moves idle and incompressible pages from RAM to a loop-backed file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = features.zramBackingEnabled,
                        onCheckedChange = onBackingToggled,
                        enabled = backingSupported && sizeSupported
                    )
                }

                if (features.zramBackingEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "BACKING FILE SIZE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ChipRow(
                        options = backingSizeOptions,
                        selected = features.zramBackingSizeMb,
                        enabled = backingSupported,
                        label = { formatZramMb(it) },
                        onSelected = onBackingSizeSelected
                    )
                    if (features.zramBackingDevice.isNotBlank()) {
                        Text(
                            text = "Attached as ${features.zramBackingDevice.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                UnsupportedReason(features.reasonFor(TuningFeature.ZRAM_BACKING))

                if (features.availableZramAlgorithms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "COMPRESSOR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ChipRow(
                        options = features.availableZramAlgorithms,
                        selected = features.zramCompAlgorithm,
                        enabled = compressorSupported,
                        label = { it },
                        onSelected = onCompressorSelected
                    )
                }
                UnsupportedReason(features.reasonFor(TuningFeature.ZRAM_COMPRESSOR))

                if (features.zramBackingEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Writeback budget",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Caps how much data may be written to the backing file",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = features.zramWritebackLimitEnabled,
                            onCheckedChange = onWritebackLimitToggled,
                            enabled = writebackSupported
                        )
                    }

                    if (features.zramWritebackLimitEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        ChipRow(
                            options = limitOptions,
                            selected = features.zramWritebackLimitMb,
                            enabled = writebackSupported,
                            label = { formatZramMb(it) },
                            onSelected = onWritebackLimitSelected
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onWriteback(ZramWritebackMode.IDLE) },
                            enabled = writebackSupported,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Write back idle")
                        }
                        OutlinedButton(
                            onClick = { onWriteback(ZramWritebackMode.HUGE) },
                            enabled = writebackSupported,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Write back huge")
                        }
                    }
                    Text(
                        text = "${formatZramMb(features.zramWritebackMb)} written to backing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    UnsupportedReason(features.reasonFor(TuningFeature.ZRAM_WRITEBACK))
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                enabled = enabled,
                label = { Text(label(option), style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

private fun formatZramMb(mb: Long): String =
    if (mb >= 1024) String.format("%.1f GB", mb / 1024f) else "$mb MB"
