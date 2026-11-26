package app.tfdn.lirelle.feature.player

/** The speeds a tap away: the shortcuts of the speed sheet, and the ones the car's speed button goes through. */
val SpeedShortcuts = listOf(1f, 1.25f, 1.5f, 2f)

/** Two speeds this close are the same: a float's error, not a step. */
internal const val SameSpeed = 0.001f

/**
 * The shortcut after [current]: the next higher one, the first after the last. A speed that is no shortcut
 * (set by the sheet's slider) goes to the next higher one too.
 */
fun nextSpeedShortcut(current: Float): Float =
    SpeedShortcuts.firstOrNull { it > current + SameSpeed } ?: SpeedShortcuts.first()
