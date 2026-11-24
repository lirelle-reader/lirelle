package app.tfdn.lirelle.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Width of the app window, following the Material 3 breakpoints.
 * Screens branch on this, never on the device type: a tablet in split screen is Compact.
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        private const val MediumMinDp = 600f
        private const val ExpandedMinDp = 840f

        fun fromWidthDp(widthDp: Float): WindowWidthClass = when {
            widthDp < MediumMinDp -> Compact
            widthDp < ExpandedMinDp -> Medium
            else -> Expanded
        }
    }
}

val LocalWindowWidthClass = staticCompositionLocalOf { WindowWidthClass.Compact }

/** Reads the current window width. Call once near the root and provide [LocalWindowWidthClass]. */
@Composable
fun currentWindowWidthClass(): WindowWidthClass {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = widthPx / LocalDensity.current.density
    return WindowWidthClass.fromWidthDp(widthDp)
}
