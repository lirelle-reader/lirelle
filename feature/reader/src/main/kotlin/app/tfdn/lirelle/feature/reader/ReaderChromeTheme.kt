package app.tfdn.lirelle.feature.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** How much of the link colour tints a container, and of the text colour a raised surface or an outline. */
private const val ContainerTint = 0.2f
private const val RaisedTint = 0.06f

/** The track of a switch that is off: it has to show on the bars' colour. */
private const val FaintTint = 0.12f
private const val OutlineTint = 0.6f
private const val FaintOutlineTint = 0.25f

/**
 * The reader's own surfaces (bars, settings sheets, panels and dialogs) in the colours of the
 * reading theme, not of the app: a sepia page has sepia bars. Type and shapes stay those of the app.
 */
@Composable
fun ReaderChromeTheme(colours: ThemeColours, content: @Composable () -> Unit) {
    val scheme = remember(colours) { colours.toColorScheme() }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * A Material colour scheme made of the five colours of a reading theme: the bars' colours for
 * surfaces and what is written on them, the page's for the background, the link colour for the
 * accent. Every "on" colour is the theme's text colour on a surface close to the bars' or the
 * page's, or the page colour on the link colour, so that text keeps its contrast in every theme;
 * what is disabled is that text colour faded, as Material does it. Nothing is tinted by elevation.
 */
internal fun ThemeColours.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val container = lerp(barBackground, link, ContainerTint)
    val raised = lerp(barBackground, barForeground, RaisedTint)
    val faint = lerp(barBackground, barForeground, FaintTint)
    return base.copy(
        primary = link,
        onPrimary = background,
        primaryContainer = container,
        onPrimaryContainer = barForeground,
        inversePrimary = background,
        secondary = link,
        onSecondary = background,
        secondaryContainer = container,
        onSecondaryContainer = barForeground,
        tertiary = link,
        onTertiary = background,
        tertiaryContainer = container,
        onTertiaryContainer = barForeground,
        background = background,
        onBackground = foreground,
        surface = barBackground,
        onSurface = barForeground,
        surfaceVariant = raised,
        onSurfaceVariant = barForeground,
        surfaceTint = Color.Transparent,
        inverseSurface = barForeground,
        inverseOnSurface = barBackground,
        outline = lerp(barBackground, barForeground, OutlineTint),
        outlineVariant = lerp(barBackground, barForeground, FaintOutlineTint),
        scrim = Color.Black,
        surfaceBright = raised,
        surfaceDim = barBackground,
        surfaceContainerLowest = barBackground,
        surfaceContainerLow = barBackground,
        surfaceContainer = barBackground,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = faint,
    )
}
