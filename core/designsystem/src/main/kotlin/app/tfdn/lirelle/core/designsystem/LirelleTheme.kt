package app.tfdn.lirelle.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app theme: the fixed Lirelle palette (see Color.kt), light or dark following the system.
 * Screens only read MaterialTheme, so a change of palette here touches no screen.
 */
@Composable
fun LirelleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LirelleDarkColors else LirelleLightColors,
        typography = LirelleTypography,
        shapes = LirelleShapes,
        content = content,
    )
}
