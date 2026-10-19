package com.pox.kernelmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.ui.components.*
import com.pox.kernelmanager.ui.theme.PoxShape
import com.pox.kernelmanager.ui.viewmodel.FeaturesViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            RootStatusBanner(
                granted = uiState.features.rootGranted,
                providerLabel = uiState.features.rootProviderLabel
            )

            // 1. Gaming Mode & KProfiles
            SectionHeader(
                title = "Gaming & Performance Governor",
                subtitle = "MediaTek perfmgr EAS boost + FPSGO touch boost"
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Performance Profile",
                        style = MaterialTheme.typography.titleMedium,
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
                subtitle = "Direct hardware bypass & battery longevity ceilings"
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
                valueRange = 50f..100f,
                steps = 9,
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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
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
                        shape = PoxShape.button
                    ) {
                        Text("Reclaim")
                    }
                }
            }

            // 4. Display, Touch & Vibrator
            SectionHeader(
                title = "Display, Touch & Haptics",
                subtitle = "Wake gestures & tactile feedback"
            )

            FeatureSwitchCard(
                title = "Double Tap to Wake (DT2W)",
                description = "Wake screen by double tapping touch panel in deep sleep",
                isChecked = uiState.features.doubleTapToWake,
                onCheckedChange = { viewModel.toggleDt2w(it) },
                icon = Icons.Default.TouchApp,
                enabled = uiState.features.isSupported(TuningFeature.DT2W)
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

            // 5. Storage & Filesystem
            SectionHeader(
                title = "Storage & Dynamic Fsync",
                subtitle = "Micro-stutter elimination & UFS flash scheduler"
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
                    text = "I/O SCHEDULER",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp)) {
                    uiState.features.availableSchedulers.forEachIndexed { index, scheduler ->
                        SegmentedButton(
                            selected = uiState.features.ioScheduler == scheduler,
                            onClick = { viewModel.setIoScheduler(scheduler) },
                            enabled = uiState.features.isSupported(TuningFeature.IO_SCHEDULER),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = uiState.features.availableSchedulers.size
                            )
                        ) {
                            Text(scheduler, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
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

            if (uiState.features.isSupported(TuningFeature.ZRAM_SIZE)) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ZRAM BACKING SIZE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    listOf(128L, 256L, 512L, 1024L, 2048L).forEach { size ->
                        FilterChip(
                            selected = uiState.features.zramSizeMb == size,
                            onClick = { viewModel.setZramSizeMb(size) },
                            label = { Text("${size}M", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
