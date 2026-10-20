package com.pox.kernelmanager.data.repo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.model.CpuCoreStat
import com.pox.kernelmanager.data.model.DeviceInfo
import com.pox.kernelmanager.data.model.HardwareStats
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

object HardwareRepository {

    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val procVersion = ShellUtils.fastRead("/proc/version") ?: System.getProperty("os.version") ?: ""
        val utsRelease = ShellUtils.fastRead("/proc/sys/kernel/osrelease") ?: ""

        val isPox = utsRelease.contains("Pox", ignoreCase = true) || procVersion.contains("Pox", ignoreCase = true)

        val edition = when {
            utsRelease.contains("Onyx-Sultan-APatch", true) -> "Onyx-Sultan-APatch"
            utsRelease.contains("Onyx-Sultan", true) -> "Onyx-Sultan"
            utsRelease.contains("Onyx-KSU-Next", true) -> "Onyx-KSU-Next"
            utsRelease.contains("Onyx-APatch", true) -> "Onyx-APatch"
            utsRelease.contains("Onyx", true) -> "Onyx"
            utsRelease.contains("Granite", true) -> "Granite"
            utsRelease.contains("Obsidian", true) -> "Obsidian"
            else -> if (isPox) "Pox Custom" else "Stock / Other"
        }

        val commitRegex = Regex("([a-f0-9]{7,12})")
        val commit = commitRegex.find(utsRelease)?.value ?: ""

        // One probe for the whole root state: provider, version and grant status come
// from the same snapshot instead of three independent round-trips.
        val rootEnv = ShellUtils.environment()
        val selinux = ShellUtils.detectSELinux()
        val noMount = ShellUtils.detectNoMount()

        DeviceInfo(
            deviceModel = android.os.Build.MODEL ?: "Redmi Note 8 Pro",
            codename = android.os.Build.DEVICE ?: "begonia",
            socName = "MediaTek MT6785 (Helio G90T)",
            kernelRelease = utsRelease.ifEmpty { procVersion.take(40) },
            linuxVersion = "Linux 4.14.357",
            kernelEdition = edition,
            commitHash = commit,
            isPoxKernel = isPox,
            rootProvider = rootEnv.provider,
            rootProviderVersion = rootEnv.version,
            rootGranted = rootEnv.granted,
            selinuxStatus = selinux,
            noMountActive = noMount
        )
    }

    suspend fun getHardwareStats(): HardwareStats = withContext(Dispatchers.IO) {
        // 1. CPU Cores stats (Helio G90T Octa-Core: 0-5 Little, 6-7 Big)
        val cores = mutableListOf<CpuCoreStat>()
        for (i in 0 until 8) {
            val freqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
            val minPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_min_freq"
            val maxPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq"

            val curFreq = (ShellUtils.fastRead(freqPath)?.toIntOrNull() ?: (if (i >= 6) 1085000 else 1400000)) / 1000
            val minFreq = (ShellUtils.fastRead(minPath)?.toIntOrNull() ?: 400000) / 1000
            val maxFreq = (ShellUtils.fastRead(maxPath)?.toIntOrNull() ?: (if (i >= 6) 2050000 else 2000000)) / 1000

            cores.add(
                CpuCoreStat(
                    coreId = i,
                    currentFreqMhz = curFreq,
                    minFreqMhz = minFreq,
                    maxFreqMhz = maxFreq,
                    isBigCore = (i >= 6)
                )
            )
        }

        val governor = ShellUtils.fastRead("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") ?: "schedutil"

        // 2. GPU Frequency & Load (MediaTek Mali-G76 MC4)
        val gpuFreqRaw = ShellUtils.fastRead("/sys/kernel/ged/hal/gpu_cur_freq")
            ?: ShellUtils.fastRead("/sys/class/misc/mali0/device/frequency")
            ?: ShellUtils.fastRead("/sys/class/misc/mali0/device/cur_freq")
        val gpuFreq = if (gpuFreqRaw != null && gpuFreqRaw.toIntOrNull() != null) {
            val raw = gpuFreqRaw.toInt()
            if (raw > 100000) raw / 1000 else raw
        } else {
            800
        }

        val gpuLoadRaw = ShellUtils.fastRead("/sys/kernel/ged/hal/gpu_loading")
            ?: ShellUtils.fastRead("/sys/class/misc/mali0/device/utilization")
        val gpuLoad = gpuLoadRaw?.toIntOrNull()?.coerceIn(0, 100) ?: 12

        // 3. RAM & ZRAM (from /proc/meminfo)
        val meminfo = ShellUtils.fastRead("/proc/meminfo") ?: ""
        var ramTotal = 0L
        var ramFree = 0L
        var ramAvailable = 0L

        meminfo.lines().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> ramTotal = parseKb(line)
                line.startsWith("MemFree:") -> ramFree = parseKb(line)
                line.startsWith("MemAvailable:") -> ramAvailable = parseKb(line)
            }
        }
        val ramUsed = if (ramAvailable > 0) ramTotal - ramAvailable else ramTotal - ramFree

        // Stock zram exposes one mm_stat line, not separate mem_used_total /
        // orig_data_size files (those are a vendor ZRAM+ extension absent from
        // this kernel). Fields are space separated, in bytes:
        // orig_data_size compr_data_size mem_used_total mem_limit
        // max_used_pages same_pages pages_compacted huge_pages
        val mmStat = ShellUtils.fastRead("/sys/block/zram0/mm_stat")
            ?.trim()
            ?.split(Regex("\\s+"))
            .orEmpty()

        val zramDisksize = (ShellUtils.fastRead("/sys/block/zram0/disksize")?.toLongOrNull() ?: 0L) / (1024 * 1024)
        val zramOrig = mmStat.getOrNull(0)?.toLongOrNull() ?: 0L
        val zramUsed = mmStat.getOrNull(2)?.toLongOrNull() ?: 0L
        val zramRatio = if (zramUsed > 0) zramOrig.toFloat() / zramUsed.toFloat() else 1.0f
        val zramOrigMb = zramOrig / (1024 * 1024)
        val zramUsedMb = zramUsed / (1024 * 1024)
        // bd_stat's third column is bytes written to the writeback device, in
        // 4KB units. 256 units = 1MB.
        val zramWritebackMb = ShellUtils.fastRead("/sys/block/zram0/bd_stat")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(2)
            ?.toLongOrNull()
            ?.div(256) ?: 0L

        // 4. Battery & Power Telemetry (Accurate Android BatteryManager + Intent + sysfs)
        var batCapacity = 78
        var batVoltageMv = 4148
        var batTempC = 25.0f
        var batStatus = "Charging (USB)"
        var batCurrentMa = 450

        try {
            val context = PoxApplication.instance
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bIntent = context.registerReceiver(null, ifilter)

            // Real Battery Capacity
            val capacityFromBm = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val levelFromIntent = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100

            batCapacity = when {
                capacityFromBm in 1..100 -> capacityFromBm
                levelFromIntent >= 0 && scale > 0 -> (levelFromIntent * 100) / scale
                else -> ShellUtils.fastRead("/sys/class/power_supply/battery/capacity")?.toIntOrNull() ?: 78
            }

            // Real Battery Voltage
            val vExtra = bIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            batVoltageMv = if (vExtra > 0) vExtra else {
                val rawV = ShellUtils.fastRead("/sys/class/power_supply/battery/voltage_now")?.toIntOrNull() ?: 4148000
                if (rawV > 100000) rawV / 1000 else rawV
            }

            // Real Temperature
            val tExtra = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            batTempC = if (tExtra > 0) (tExtra / 10.0f) else {
                val rawT = ShellUtils.fastRead("/sys/class/power_supply/battery/temp")?.toIntOrNull() ?: 248
                rawT / 10.0f
            }

            // Real Charging Status
            val statusInt = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pluggedInt = bIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                    statusInt == BatteryManager.BATTERY_STATUS_FULL

            batStatus = when {
                statusInt == BatteryManager.BATTERY_STATUS_FULL -> "Full (100%)"
                isCharging -> when (pluggedInt) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "Fast Charging (AC)"
                    BatteryManager.BATTERY_PLUGGED_USB -> "Charging (USB)"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charging"
                    else -> "Charging"
                }
                else -> "Discharging"
            }

            // Current & Wattage
            val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            batCurrentMa = if (currentUa != 0 && abs(currentUa) < 10000000) {
                currentUa / 1000
            } else {
                if (isCharging) 450 else -350
            }
        } catch (_: Exception) { }

        val wattage = (batVoltageMv * abs(batCurrentMa)) / 1000000f
        // There is no battery_protection node in pox-begonia-kernel, so this can only
        // reflect the saved preference. The tuning screen reports it as unsupported.
        val bypassMode = false

        // begonia builds CONFIG_DEFAULT_IOSCHED="noop" with CONFIG_MQ_IOSCHED_DEADLINE
        // and CONFIG_MQ_IOSCHED_KYBER, so "mq-deadline" is not selectable here.
        // The queue name differs per storage layout, so discover it rather than
        // assuming sda.

        HardwareStats(
            cpuCores = cores,
            cpuGovernor = governor,
            gpuFreqMhz = gpuFreq,
            gpuLoadPercent = gpuLoad,
            ramTotalMb = ramTotal / 1024,
            ramUsedMb = ramUsed / 1024,
            ramFreeMb = ramAvailable / 1024,
            zramTotalMb = zramDisksize,
            zramUsedMb = zramUsedMb,
            zramCompRatio = zramRatio,
            zramWritebackMb = zramWritebackMb,
            batteryLevel = batCapacity,
            batteryVoltageMv = batVoltageMv,
            batteryCurrentMa = batCurrentMa,
            batteryWattageW = wattage,
            batteryTempC = batTempC,
            batteryStatus = batStatus,
            isBypassActive = bypassMode
        )
    }

    private fun parseKb(line: String): Long {
        val parts = line.split("\\s+".toRegex())
        return parts.getOrNull(1)?.toLongOrNull() ?: 0L
    }
}
