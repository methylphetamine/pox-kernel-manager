package com.pox.kernelmanager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pox.kernelmanager.data.AppSettings

/**
 * Editor for the GitHub repository the kernel updater reads releases from.
 *
 * Shared between the Settings screen and the Updater screen so both stay in
 * sync with the value stored in app preferences.
 */
@Composable
fun RepositoryEditorDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    val normalized = AppSettings.normalizeRepository(text)
    val showError = text.isNotBlank() && normalized == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updater repository") },
        text = {
            Column {
                Text(
                    text = "Releases are read from https://api.github.com/repos/<owner>/<repo>. " +
                        "Paste an owner/repo pair or a full GitHub URL.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("owner/repo") },
                    placeholder = { Text(AppSettings.DEFAULT_UPDATER_REPOSITORY) },
                    isError = showError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                )
                if (showError) {
                    Text(
                        text = "Use the owner/repo form, e.g. ${AppSettings.DEFAULT_UPDATER_REPOSITORY}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                AppSettings.UPDATER_REPOSITORY_PRESETS.forEach { preset ->
                    SuggestionChip(
                        onClick = { text = preset },
                        label = { Text(preset, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onSave) },
                enabled = normalized != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
