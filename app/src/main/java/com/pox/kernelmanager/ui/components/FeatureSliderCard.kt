package com.pox.kernelmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pox.kernelmanager.ui.theme.PoxShape
import kotlin.math.abs

/**
 * Slider card for a kernel value.
 *
 * The thumb never talks to the kernel. Writes used to fire on every pixel of
 * movement, and each one queued a root shell round-trip that re-read the whole
 * tuning state afterwards - a single drag could pile up dozens of them, which
 * is what made every slider in the app stutter. The card now keeps its own
 * value while the thumb moves and commits exactly once, when the gesture ends.
 *
 * [presets] adds one-tap chips (with a Custom entry that accepts a typed value
 * the slider steps cannot reach); it is off by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeatureSliderCard(
    title: String,
    valueLabel: (Float) -> String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    presets: List<Float>? = null,
    presetSuffix: String = ""
) {
    val contentAlpha = if (enabled) 1f else 0.6f

    // While the user is dragging, this is the value on screen; it is adopted
    // from [value] whenever the outside world reports a new committed one.
    var dragValue by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { dragValue = value }

    var showCustomDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = PoxShape.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                Surface(
                    shape = PoxShape.pill,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = contentAlpha)
                ) {
                    Text(
                        text = valueLabel(dragValue),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = contentAlpha),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Slider(
                value = dragValue,
                onValueChange = { dragValue = it },
                onValueChangeFinished = { onValueCommit(dragValue) },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = valueLabel(dragValue) }
            )
            if (!presets.isNullOrEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = abs(value - preset) < 0.5f,
                            onClick = {
                                dragValue = preset
                                onValueCommit(preset)
                            },
                            enabled = enabled,
                            label = {
                                Text(
                                    text = "${preset.toInt()}$presetSuffix",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    // Steps cover the usual values; this reaches the ones they
                    // cannot (e.g. an 85% charge stop).
                    FilterChip(
                        selected = presets.none { abs(value - it) < 0.5f },
                        onClick = { showCustomDialog = true },
                        enabled = enabled,
                        label = {
                            Text(text = "Custom", style = MaterialTheme.typography.labelSmall)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomValueDialog(
            title = title,
            initial = dragValue,
            valueRange = valueRange,
            suffix = presetSuffix,
            onDismiss = { showCustomDialog = false },
            onConfirm = { picked ->
                showCustomDialog = false
                dragValue = picked
                onValueCommit(picked)
            }
        )
    }
}

/** Free-form entry for a value between the slider's endpoints. */
@Composable
private fun CustomValueDialog(
    title: String,
    initial: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    suffix: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    val min = valueRange.start.toInt()
    val max = valueRange.endInclusive.toInt()

    var text by remember { mutableStateOf(initial.toInt().toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in min..max }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { updated -> text = updated.filter { it.isDigit() }.take(3) },
                label = { Text("Value ($min-$max$suffix)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = text.isNotEmpty() && parsed == null,
                supportingText = if (text.isNotEmpty() && parsed == null) {
                    { Text("Enter a value between $min and $max") }
                } else {
                    null
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(it.toFloat()) } },
                enabled = parsed != null
            ) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
