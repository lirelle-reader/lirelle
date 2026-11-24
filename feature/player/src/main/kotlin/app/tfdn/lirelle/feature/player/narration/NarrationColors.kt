package app.tfdn.lirelle.feature.player.narration

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The reader theme's colours, passed in by the reader (the player module does not know its themes).
 *
 * @property background the bars' background (the reader's bar colour).
 * @property foreground the text on it.
 * @property muted secondary text: times, subtitles.
 * @property accent the narration's colour: the progress, the play button, the active timer.
 * @property onAccent what is drawn on [accent].
 * @property track the part of a progress line not played yet.
 * @property outline the hairline above a bar, and the outlined buttons' border.
 */
data class NarrationColors(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val track: Color,
    val outline: Color,
)

/** How much of the foreground tints the background of the tiles and the sheets over the panel. */
private const val ContainerTint = 0.06f
private const val ContainerHighTint = 0.10f
private const val ContainerHighestTint = 0.14f

/**
 * The app's theme with the reader's colours: what the 3a pieces reused here (the section bar, the tiles, the speed,
 * sleep and sections sheets) draw with the scheme's colours draws with [colors]. Text is [NarrationColors.foreground].
 */
@Composable
internal fun NarrationTheme(colors: NarrationColors, content: @Composable () -> Unit) {
    val tint = { share: Float -> lerp(colors.background, colors.foreground, share) }
    val scheme = MaterialTheme.colorScheme.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        background = colors.background,
        onBackground = colors.foreground,
        surface = colors.background,
        onSurface = colors.foreground,
        surfaceVariant = colors.track,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.background,
        surfaceContainer = tint(ContainerTint),
        surfaceContainerHigh = tint(ContainerHighTint),
        surfaceContainerHighest = tint(ContainerHighestTint),
        secondaryContainer = colors.track,
        onSecondaryContainer = colors.foreground,
        outline = colors.outline,
        outlineVariant = colors.track,
        inverseSurface = colors.foreground,
        inverseOnSurface = colors.background,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides colors.foreground, content = content)
    }
}
