package app.tfdn.lirelle.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The Lirelle palette, taken from the launcher icon: night blue ground, amber accent, cream ink.
// Background and surface are the same colour on purpose, and the containers (navigation bar
// and rail, cards) are only a small step away from it, so a screen never reads as grey blocks
// laid on a different background.

internal val LirelleDarkColors = darkColorScheme(
    primary = Color(0xFFF2B544),
    onPrimary = Color(0xFF2B1E00),
    primaryContainer = Color(0xFF5A4410),
    onPrimaryContainer = Color(0xFFFFE1A3),
    inversePrimary = Color(0xFF7A5900),
    secondary = Color(0xFFB9C3EA),
    onSecondary = Color(0xFF1E2747),
    secondaryContainer = Color(0xFF343F68),
    onSecondaryContainer = Color(0xFFDCE2FF),
    tertiary = Color(0xFF7FD1C4),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF1F4F47),
    onTertiaryContainer = Color(0xFFA8EFE2),
    background = Color(0xFF12172B),
    onBackground = Color(0xFFECE7DC),
    surface = Color(0xFF12172B),
    onSurface = Color(0xFFECE7DC),
    surfaceVariant = Color(0xFF2B3355),
    onSurfaceVariant = Color(0xFFB9BCCB),
    surfaceTint = Color(0xFFF2B544),
    inverseSurface = Color(0xFFECE7DC),
    inverseOnSurface = Color(0xFF2A2E40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8589A0),
    outlineVariant = Color(0xFF3A4160),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3352),
    surfaceDim = Color(0xFF0E1222),
    surfaceContainerLowest = Color(0xFF0D1120),
    surfaceContainerLow = Color(0xFF161B33),
    surfaceContainer = Color(0xFF1A203A),
    surfaceContainerHigh = Color(0xFF222949),
    surfaceContainerHighest = Color(0xFF2B3355),
)

internal val LirelleLightColors = lightColorScheme(
    primary = Color(0xFF1F2A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF0B1640),
    inversePrimary = Color(0xFFB9C3EA),
    secondary = Color(0xFF7A5900),
    onSecondary = Color(0xFFFFFFFF),
    // The amber of the icon rather than a pale tint of it: the pill of the selected
    // destination must be told from the cream bar and rail it lies on.
    secondaryContainer = Color(0xFFF2B544),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF00695C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA8EFE2),
    onTertiaryContainer = Color(0xFF00201B),
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF1B1E2E),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1B1E2E),
    surfaceVariant = Color(0xFFE3DFD3),
    onSurfaceVariant = Color(0xFF474A5C),
    surfaceTint = Color(0xFF1F2A55),
    inverseSurface = Color(0xFF2F3243),
    inverseOnSurface = Color(0xFFF3EFE6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF777A8C),
    outlineVariant = Color(0xFFC8C6BD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8F1),
    surfaceDim = Color(0xFFDCD8CD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1E8),
    surfaceContainer = Color(0xFFEFEBE1),
    surfaceContainerHigh = Color(0xFFE9E5DA),
    surfaceContainerHighest = Color(0xFFE3DFD3),
)
