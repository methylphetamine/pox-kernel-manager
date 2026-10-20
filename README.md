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
* **Accurate Hardware & Battery Telemetry**:
  * Real-time battery percentage, voltage, battery temperature, and charging status via Android `BatteryManager`.
  * Live octa-core CPU frequency monitor (Helio G90T 2x Cortex-A76 + 6x Cortex-A55).
  * Mali-G76 MC4 GPU load and clock speed tracker.
  * ZRAM and RAM allocation breakdown with live compression ratio calculation.
* **Kernel Tuning & Power Governance**:
  * Unified `/proc/perfmgr/gaming_mode` controller (Balanced, Gaming, Extreme).
  * Direct-Power Bypass Charging mode to eliminate battery heating during heavy gaming.
  * 240Hz Touch Gaming & Debounce driver toggles.
  * HyperOS `xswapd` daemon toggle and on-demand 100MB force proactive memory reclamation.
  * Hardware thermal guard and charging stop threshold limiter.
* **ZRAM & Storage Tuning**:
  * Resize the compressed swap device safely: the app performs the required `swapoff` -> `reset` -> `disksize` -> `mkswap` -> `swapon` sequence instead of writing to a busy node.
  * Select the zram compressor from the algorithms the kernel actually advertises (LZ4, LZO, LZ4HC, ZSTD, deflate).
  * **ZRAM disk writeback**: create a sparse backing file on `/data`, attach it to a free loop device, and hand that block device to `CONFIG_ZRAM_WRITEBACK`.
  * Mark idle pages and write back idle or incompressible pages on demand, with an optional writeback budget and live `bd_stat` accounting.
  * I/O scheduler picker reads the physical UFS queue (not zram/loop), supports `none`, `mq-deadline`, `kyber` and `bfq`, and skips queues that do not advertise the selection instead of reporting a false failure.
* **OTA Kernel Updater**:
  * Queries official GitHub release matrix directly from `POX-project/pox-begonia-kernel`.
  * One-tap download and SHA-256 verification of AnyKernel3 flash packages.
* **Diagnostics & SELinux**:
  * Live kernel ring buffer (`dmesg`) viewer with search and log exporting.
  * One-tap complete hardware report generation to `/sdcard/Download/`.

---

## 🚀 Building from Source

### Prerequisites
* JDK 17+
* Android SDK (API 34 / Build Tools 34.0.0)

```bash
git clone https://github.com/POX-project/pox-kernel-manager.git
cd pox-kernel-manager
./gradlew assembleDebug
```

The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🇵🇸 Solidarity
Stand for Justice. Free Palestine. 🕊️
