package com.pox.kernelmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One radius ladder for the whole app.
 *
 *  - hero   28dp — the single "expressive" surface per screen (overview/status)
 *  - card   20dp — standard grouped cards
 *  - panel  16dp — inline notes and secondary cards
 *  - pill   12dp — badges, value chips, icon tiles
 *  - button 14dp — every button shape, so they read as one control family
 */
object PoxShape {
    val hero = RoundedCornerShape(28.dp)
    val card = RoundedCornerShape(20.dp)
    val panel = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(14.dp)
}

/**
 * The same ladder feeds Material's own shapes so default-styled components
 * (dialogs, chips, menus, snackbars) cannot drift from it.
 */
val PoxShapes = Shapes(
    extraSmall = PoxShape.pill,
    small = PoxShape.pill,
    medium = PoxShape.panel,
    large = PoxShape.card,
    extraLarge = PoxShape.hero
)
