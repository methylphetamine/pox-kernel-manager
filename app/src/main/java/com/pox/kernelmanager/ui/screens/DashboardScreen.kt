package com.pox.kernelmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pox.kernelmanager.BuildConfig
import com.pox.kernelmanager.ui.components.PoxTopAppBar
import com.pox.kernelmanager.ui.components.SectionHeader
import com.pox.kernelmanager.ui.components.StatCard
import com.pox.kernelmanager.ui.components.StatusBadge
import com.pox.kernelmanager.ui.theme.PoxShape
import com.pox.kernelmanager.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Root can be granted while the app is backgrounded, so re-probe on resume.
    // The monitor loop is tied to this composable: it starts only while the
    // Dashboard tab is visible and the activity is at least STARTED, and is
    // cancelled on ON_PAUSE or when the composable is disposed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startHardwareMonitorLoop()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAppBackgrounded()
        }
    }

    Scaffold(
        topBar = {
            PoxTopAppBar(
                title = "Pox Kernel",
                onRefresh = { viewModel.refresh() },
                isRefreshing = uiState.isLoading
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // Hero card: the screen's single expressive surface.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = PoxShape.hero,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Pox Kernel",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = uiState.deviceInfo.kernelEdition.ifEmpty { "Mainline" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        Surface(
                            shape = PoxShape.pill,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            // Read from BuildConfig rather than a literal, which had drifted from
                            // app/build.gradle.kts and reported the wrong version.
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = uiState.deviceInfo.kernelRelease.ifEmpty { "4.14.357-Pox-Kernel" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${uiState.deviceInfo.deviceModel} (${uiState.deviceInfo.codename}) • ${uiState.deviceInfo.socName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (uiState.deviceInfo.rootProviderVersion.isNotBlank()) {
                        Text(
                            text = uiState.deviceInfo.rootProviderVersion,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    // Badges wrap at large font scales instead of clipping.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // UNKNOWN is a framework we could not identify, not a rooted device:
                        // showing it as an active badge used to report a false positive.
                        StatusBadge(
                            text = uiState.deviceInfo.rootProvider.displayName,
                            isActive = uiState.deviceInfo.rootProvider.isRooted &&
                                    uiState.deviceInfo.rootGranted,
                            activeColor = MaterialTheme.colorScheme.primary
                        )
                        StatusBadge(
                            text = "SELinux: ${uiState.deviceInfo.selinuxStatus}",
                            isActive = uiState.deviceInfo.selinuxStatus.equals("Enforcing", true),
                            activeColor = MaterialTheme.colorScheme.primary
                        )
                        StatusBadge(
                            text = if (uiState.deviceInfo.noMountActive) "NoMount Active" else "NoMount Inactive",
                            isActive = uiState.deviceInfo.noMountActive,
                            activeColor = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // Section: Battery & Power
            SectionHeader(
                title = "Battery & Power Subsystem",
                subtitle = "Live charging telemetry, thermals & direct bypass"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Battery Level",
                    value = "${uiState.stats.batteryLevel}%",
                    icon = Icons.Default.BatteryChargingFull,
                    subtitle = "${uiState.stats.batteryStatus} (${String.format("%.1f", uiState.stats.batteryWattageW)}W)",
                    progress = uiState.stats.batteryLevel / 100f,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Temperature",
                    value = "${String.format("%.1f", uiState.stats.batteryTempC)}°C",
                    icon = Icons.Default.Thermostat,
                    subtitle = if (uiState.stats.isBypassActive) "Bypass Active" else "${uiState.stats.batteryVoltageMv}mV / ${uiState.stats.batteryCurrentMa}mA",
                    modifier = Modifier.weight(1f)
                )
            }

            // Section: CPU Complex (Helio G90T 8-cores)
            SectionHeader(
                title = "CPU & Schedutil Complex",
                subtitle = "2x Cortex-A76 Big Cores + 6x Cortex-A55 Little Cores"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val bigCoreFreq = uiState.stats.cpuCores.getOrNull(6)?.currentFreqMhz ?: 2050
                val littleCoreFreq = uiState.stats.cpuCores.getOrNull(0)?.currentFreqMhz ?: 1400

                StatCard(
                    title = "Cortex-A76 (Big)",
                    value = "${bigCoreFreq} MHz",
                    icon = Icons.Default.Speed,
                    subtitle = "Core 6, 7 • Max 2050 MHz",
                    progress = bigCoreFreq / 2050f,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Cortex-A55 (Little)",
                    value = "${littleCoreFreq} MHz",
                    icon = Icons.Default.Memory,
                    subtitle = "Core 0..5 • Governor: ${uiState.stats.cpuGovernor}",
                    progress = littleCoreFreq / 2000f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Section: GPU & Memory Compression
            SectionHeader(
                title = "Mali-G76 GPU & ZRAM Engine",
                subtitle = "Bifrost 4-Core GPU and iOS-style compressed RAM cushion"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Mali-G76 MC4",
                    value = "${uiState.stats.gpuFreqMhz} MHz",
                    icon = Icons.Default.Games,
                    subtitle = "Load: ${uiState.stats.gpuLoadPercent}%",
                    progress = uiState.stats.gpuLoadPercent / 100f,
                    modifier = Modifier.weight(1f)
                )
                val ramUsed = uiState.stats.ramUsedMb
                val ramTotal = uiState.stats.ramTotalMb.coerceAtLeast(1)
                StatCard(
                    title = "RAM / ZRAM",
                    value = "${ramUsed / 1024}G / ${ramTotal / 1024}G",
                    icon = Icons.Default.Storage,
                    subtitle = buildString {
                        append("ZRAM: ${uiState.stats.zramUsedMb}M")
                        append(" (Ratio: ${String.format("%.2f", uiState.stats.zramCompRatio)}x)")
                        if (uiState.stats.zramWritebackMb > 0) {
                            append(" • WB: ${uiState.stats.zramWritebackMb}M")
                        }
                    },
                    progress = ramUsed.toFloat() / ramTotal.toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
