package com.pox.kernelmanager.tiles

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.R
import com.pox.kernelmanager.data.model.GamingModeLevel
import com.pox.kernelmanager.data.repo.TuningApplyResult
import com.pox.kernelmanager.data.repo.TuningRepository
import com.pox.kernelmanager.ui.theme.PoxShape
import com.pox.kernelmanager.ui.theme.PoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Level picker for the Gaming mode tile, opened by long-pressing the tile.
 *
 * Quick Settings has no long-press callback on TileService: SystemUI launches
 * whichever activity in the app declares [TileService.ACTION_QS_TILE_PREFERENCES]
 * instead, and falls back to the App info screen when there is none. The intent
 * names the tile that was held in [Intent.EXTRA_COMPONENT_NAME], so the other
 * Pox tiles keep that stock App info behaviour.
 *
 * The activity is a floating, dimmed dialog window in a task of its own
 * (singleInstance in the manifest - the recipe BegoTorch uses), so holding the
 * tile grows a small popup over the Quick Settings shade instead of pulling the
 * manager app to the foreground behind the picker.
 */
class GamingModeTileActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tap on the dimmed area outside the card dismisses the popup.
        setFinishOnTouchOutside(true)

        val heldTile = heldTileComponent()
        if (heldTile != null && heldTile.className != GamingModeTileService::class.java.name) {
            // Not the gaming tile: reproduce the default long-press behaviour.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
            finish()
            return
        }

        setContent {
            PoxTheme {
                GamingModePopup(
                    current = TuningRepository.snapshot().gamingMode,
                    onPick = ::apply
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun heldTileComponent(): ComponentName? =
        intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME) as? ComponentName

    private fun apply(level: GamingModeLevel) {
        // Close the popup first so the pick feels instant; the write continues
        // on the app scope and the result lands as a toast over the shade.
        finish()
        PoxApplication.applicationScope.launch {
            // Wait the process-start restore out first, exactly like the tile
            // does - two interleaved writes would leave the kernel on old values.
            TuningRepository.awaitSavedTuningRestored()
            val result = runCatching { TuningRepository.setGamingMode(level) }
                .getOrElse { TuningApplyResult(false, it.message ?: "Could not set gaming mode") }
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(applicationContext, result.message, Toast.LENGTH_SHORT).show()
                // Refresh the tile now instead of waiting for the panel to reopen.
                TileService.requestListeningState(
                    applicationContext,
                    ComponentName(applicationContext, GamingModeTileService::class.java)
                )
            }
        }
    }
}

/**
 * The popup card: title, the same Balanced / Gaming / Extreme row the Tuning
 * screen uses, and the selected level's description. Picking applies straight
 * away, exactly like the Tuning screen's segmented row does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamingModePopup(
    current: GamingModeLevel,
    onPick: (GamingModeLevel) -> Unit
) {
    val levels = GamingModeLevel.entries

    Surface(
        modifier = Modifier.widthIn(min = 300.dp, max = 360.dp),
        shape = PoxShape.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.tile_gaming_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(14.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = current == level,
                        onClick = { onPick(level) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size)
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
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = current.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
