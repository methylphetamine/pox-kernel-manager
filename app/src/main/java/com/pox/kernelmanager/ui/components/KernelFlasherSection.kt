package com.pox.kernelmanager.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pox.kernelmanager.data.repo.BootBackupSet
import com.pox.kernelmanager.ui.theme.PoxShape
import com.pox.kernelmanager.ui.viewmodel.FlashTask
import com.pox.kernelmanager.ui.viewmodel.FlashViewModel
import java.util.Locale

/**
 * Kernel Flasher: pick a Pox kernel zip, back up boot + dtbo, and queue the
 * install for the next recovery boot.
 */
@Composable
fun KernelFlasherSection(viewModel: FlashViewModel, modifier: Modifier = Modifier) {
    val task by viewModel.task.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val pendingNotice by viewModel.pendingNotice.collectAsState()

    var confirmFlash by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BootBackupSet?>(null) }
    var pendingDelete by remember { mutableStateOf<BootBackupSet?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::pickZip)
    }

    // Re-check on every visit: backups appear and the queued script is
    // consumed or missed while the app is away.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Kernel Flasher",
            subtitle = "Install a Pox kernel zip (APatch or FolkPatch) from storage. A boot + dtbo backup set is taken automatically first."
        )

        // ---- package card ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = PoxShape.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Kernel package",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pick a kernel zip downloaded from Telegram, Gofile or GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val info = selected
                if (info == null) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                        shape = PoxShape.button
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choose kernel zip")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = info.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatMb(info.sizeBytes)} · ${info.flavor.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "sha256 ${info.sha256.take(16)}…",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(text = info.flavor.label, isActive = true)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !busy,
                            shape = PoxShape.button
                        ) {
                            Text("Replace")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = viewModel::clearSelection, enabled = !busy) {
                            Text("Clear")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The boot + dtbo backup set is saved (Downloads and a recovery mirror) before anything is staged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { confirmFlash = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PoxShape.button
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Flash package")
                    }
                }
            }
        }

        // ---- task cards ----
        when (val current = task) {
            FlashTask.Idle -> Unit

            is FlashTask.Working -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = current.step, style = MaterialTheme.typography.bodyMedium)
                }
            }

            is FlashTask.FlashReady -> ReadyCard(
                title = "Queued for the next recovery boot",
                body = buildString {
                    append("${current.packageName} is staged and the recovery script is set. ")
                    current.backup?.let { append("Backup set ${it.id} was saved first. ") }
                    append("TWRP / OrangeFox installs the package automatically - finish with Reboot -> System.")
                },
                onReboot = viewModel::rebootToRecovery,
                onDismiss = viewModel::dismissTask
            )

            is FlashTask.RestoreReady -> ReadyCard(
                title = "Restore queued",
                body = "The next recovery boot runs ${current.backup.zipName} and writes boot + dtbo back from set ${current.backup.id}.",
                onReboot = viewModel::rebootToRecovery,
                onDismiss = viewModel::dismissTask
            )

            is FlashTask.BackupDone -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup saved",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set ${current.backup.id}: boot ${formatMb(current.backup.bootSizeBytes)}" +
                            if (current.backup.hasDtbo) " · dtbo ${formatMb(current.backup.dtboSizeBytes)}" else "" +
                            " · ${current.backup.zipName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    current.backup.downloadPath?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::dismissTask) { Text("Dismiss") }
                }
            }

            is FlashTask.Error -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Flasher notice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::dismissTask) { Text("Dismiss") }
                }
            }
        }

        // ---- queued script that never ran ----
        pendingNotice?.let { pending ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = PoxShape.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recovery did not pick up the queued install",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The script for ${pending.target.substringAfterLast('/')} survived a boot, so the recovery either " +
                            "did not find it (no TWRP / OrangeFox, or storage was not decrypted) or the install did not run. " +
                            "You can flash the package manually from the recovery's Install menu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::dismissPendingNotice) { Text("Dismiss") }
                }
            }
        }

        // ---- backups ----
        SectionHeader(
            title = "Boot + DTBO backups",
            subtitle = "Each set keeps boot.img, dtbo.img and a flashable PoxRestore zip."
        )

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(
                onClick = viewModel::createBackupNow,
                enabled = !busy,
                shape = PoxShape.button
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Create backup now")
            }
        }

        if (backups.isEmpty()) {
            Text(
                text = "No backup sets yet - one is created automatically before every flash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            backups.forEach { set ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = PoxShape.card,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = formatSetId(set.id),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "boot ${formatMb(set.bootSizeBytes)}" +
                                (if (set.hasDtbo) " · dtbo ${formatMb(set.dtboSizeBytes)}" else "") +
                                " · ${set.zipName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = set.downloadPath ?: "Recovery copy only (/data/local/tmp/PoxBackups)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { pendingRestore = set }, enabled = !busy) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(onClick = { pendingDelete = set }, enabled = !busy) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }

        // ---- bootloop help ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = PoxShape.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "If the device stops booting",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Hold Power + Volume Up to enter the recovery, choose Install and pick " +
                            "Download/PoxBackups/<set>/PoxRestore-*.zip (or the /data/local/tmp/PoxBackups copy when " +
                            "storage cannot be decrypted). Flashing it writes boot + dtbo back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ---- dialogs ----

    val info = selected
    if (confirmFlash && info != null) {
        AlertDialog(
            onDismissRequest = { confirmFlash = false },
            title = { Text("Flash ${info.displayName}?") },
            text = {
                Column {
                    Text(
                        text = "1. A boot + dtbo backup set is saved to Downloads/PoxBackups and mirrored to the recovery copy.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "2. ${info.flavor.detail.replaceFirstChar { it.uppercase() }} - the package is staged and the recovery script is queued.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "3. On the next recovery boot TWRP / OrangeFox installs it; finish with Reboot -> System.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFlash = false
                    viewModel.flash()
                }) { Text("Back up & stage") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFlash = false }) { Text("Cancel") }
            }
        )
    }

    pendingRestore?.let { set ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore set ${formatSetId(set.id)}?") },
            text = {
                Text(
                    text = "The next recovery boot runs ${set.zipName} and writes boot + dtbo back from this set. " +
                        "Anything flashed after this backup is replaced.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val backup = set
                    pendingRestore = null
                    viewModel.restore(backup)
                }) { Text("Queue restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { set ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete backup ${formatSetId(set.id)}?") },
            text = {
                Text(
                    text = "Removes boot.img, dtbo.img and the restore zip from Downloads and the recovery mirror.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val backup = set
                        pendingDelete = null
                        viewModel.deleteBackup(backup)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReadyCard(
    title: String,
    body: String,
    onReboot: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = PoxShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onReboot,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = PoxShape.button
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reboot to recovery now")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))

/** "20261019-093045" -> "2026-10-19 09:30". */
private fun formatSetId(id: String): String =
    if (id.length == 15) {
        "${id.substring(0, 4)}-${id.substring(4, 6)}-${id.substring(6, 8)} " +
            "${id.substring(9, 11)}:${id.substring(11, 13)}"
    } else {
        id
    }
