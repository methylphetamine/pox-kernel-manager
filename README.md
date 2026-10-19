# Pox Kernel Manager 🇵🇸 #FreePalestine

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Device: begonia](https://img.shields.io/badge/Device-Redmi%20Note%208%20Pro-emerald)](https://github.com/POX-project/pox-begonia-kernel)
[![Material You](https://img.shields.io/badge/Design-Material%203%20Expressive-teal)](#)

The official native companion Android application for the **Pox Kernel** (`POX-project/pox-begonia-kernel`) running on the Xiaomi Redmi Note 8 Pro (`begonia`, MediaTek MT6785 / Helio G90T). Built with pure Jetpack Compose, Kotlin Coroutines, and Google Material 3 Expressive guidelines.

---

## 🌟 Key Features

* **Dual Compatibility (Rooted & Unrooted)**:
  * Seamlessly operates on both rooted devices (KernelSU-Next, APatch, Magisk) and clean unrooted ROMs.
  * Never hangs or crashes waiting for root prompts; persists tuning options in local `SharedPreferences` when root is not granted.
* **Settings That Survive Reboots**:
  * Every tuning choice is written to on-device app storage the moment it is made.
  * A boot receiver + `JobScheduler` restore job (and a once-per-launch restore inside the app) replays the saved choices onto the kernel after a reboot or a force stop, so nothing silently resets.
  * A **Re-apply saved tuning** action in Settings fires the same restore on demand.
* **Quick Settings Tiles**:
  * One tile per tuning action: Gaming mode (cycles Balanced → Gaming → Extreme), touch boost, bypass charging, thermal guard, xswapd, double tap to wake, dynamic fsync and memory reclaim.
  * Tiles render from the saved state, so they are instant even while the root shell is busy.
* **Appearance Options**:
  * Material You (dynamic), System default, Light, or always-Dark, selected in Settings and persisted.
* **Accurate Hardware & Battery Telemetry**:
  * Real-time battery percentage, voltage, battery temperature, and charging status via Android `BatteryManager`.
  * Live octa-core CPU frequency monitor (Helio G90T 2x Cortex-A76 + 6x Cortex-A55).
  * Mali-G76 MC4 GPU load and clock speed tracker.
  * ZRAM and RAM allocation breakdown with live compression ratio calculation.
* **Kernel Tuning & Power Governance**:
  * Unified `/proc/perfmgr/gaming_mode` controller (Balanced, Gaming, Extreme). Selecting a profile pins the controller to manual mode, so the choice applies immediately instead of being treated as a dynamic-engine hint, and the UI always shows the level the kernel actually runs (Extreme is honestly reported as derated to Gaming while the battery is hot or nearly empty).
  * Direct-Power Bypass Charging mode to eliminate battery heating during heavy gaming.
  * FPSGO touch boost toggle. The old dead debounce node is gone; Gaming and Extreme modes enable the boost automatically.
  * HyperOS `xswapd` daemon toggle and on-demand 100MB force proactive memory reclamation.
  * Hardware thermal guard and charging stop threshold limiter.
* **OTA Kernel Updater**:
  * Queries the GitHub release matrix of `POX-project/pox-begonia-kernel` - or any other repository you configure in Settings or right from the Updater screen.
  * One-tap download and SHA-256 verification of AnyKernel3 flash packages.
* **Diagnostics & SELinux**:
  * Live kernel ring buffer (`dmesg`) viewer with search and log exporting.
  * One-tap complete hardware report generation to `/sdcard/Download/`.

---

## 🚀 Building from Source

### Prerequisites
* JDK 17+
* Android SDK (API 35 / Build Tools 35.0.0)

```bash
git clone https://github.com/POX-project/pox-kernel-manager.git
cd pox-kernel-manager
./gradlew assembleDebug
```

The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🇵🇸 Solidarity
Stand for Justice. Free Palestine. 🕊️
