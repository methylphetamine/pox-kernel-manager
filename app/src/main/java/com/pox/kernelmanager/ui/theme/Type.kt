package com.pox.kernelmanager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Pox type ramp.
 *
 * One deliberate scale for the whole app instead of Material defaults with
 * holes in them: compact headlines, 16sp titles, quiet 11-12sp metadata.
 * Tracking runs slightly negative at display sizes and near-neutral for body
 * text (Roboto at 14-16sp does not need Material's 0.5sp), and font padding
 * is disabled so text sits optically centred inside the cards and pills it
 * lives in.
 *
 * Weight carries meaning on this ramp:
 *  - SemiBold is the default for titles.
 *  - Bold is reserved for live numbers, alerts and statements.
 */
private fun ramp(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Float = 0f
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

val Typography = Typography(
    headlineLarge = ramp(32, 40, FontWeight.Bold, tracking = -0.5f),
    headlineMedium = ramp(24, 32, FontWeight.SemiBold, tracking = -0.25f),
    headlineSmall = ramp(22, 28, FontWeight.SemiBold, tracking = -0.1f),
    titleLarge = ramp(20, 26, FontWeight.SemiBold, tracking = -0.1f),
    titleMedium = ramp(16, 24, FontWeight.SemiBold, tracking = 0.1f),
    titleSmall = ramp(14, 20, FontWeight.Medium, tracking = 0.1f),
    bodyLarge = ramp(16, 24, FontWeight.Normal, tracking = 0.1f),
    bodyMedium = ramp(14, 20, FontWeight.Normal, tracking = 0.1f),
    bodySmall = ramp(12, 16, FontWeight.Normal, tracking = 0.2f),
    labelLarge = ramp(14, 20, FontWeight.Medium, tracking = 0.1f),
    labelMedium = ramp(12, 16, FontWeight.Medium, tracking = 0.4f),
    labelSmall = ramp(11, 16, FontWeight.Medium, tracking = 0.5f)
)
