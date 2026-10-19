package com.pox.kernelmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pox.kernelmanager.ui.theme.PoxShape

/**
 * Root status banner for the tuning screen.
 *
 * Without this the user could not tell "Magisk has not granted root to this app"
 * apart from "this kernel has no such node" — every control was simply greyed
 * out either way.
 */
@Composable
fun RootStatusBanner(
    granted: Boolean,
    providerLabel: String,
    modifier: Modifier = Modifier
) {
    val container = if (granted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (granted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = PoxShape.card,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Block,
                contentDescription = null,
                tint = onContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Top) {
                Text(
                    text = if (granted) "Root granted - $providerLabel" else "Root not granted",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (granted) {
                        "Kernel controls below are live. Anything greyed out is not implemented in this kernel build."
                    } else {
                        "Every control below is disabled because this app cannot write to the kernel. " +
                            "Open Magisk (or your root manager) > Superuser, grant Pox Kernel Manager, " +
                            "then reopen the app. If Zygisk is enabled, confirm the app is not on the " +
                            "enforce-denylist."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }
        }
    }
}
