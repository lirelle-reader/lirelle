package app.tfdn.lirelle.feature.reader.narration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.tfdn.lirelle.feature.player.narration.NarrationColors
import app.tfdn.lirelle.feature.reader.ThemeColours

/** How much of the bars' text colour makes secondary text, the progress track and the hairlines. */
private const val MutedShare = 0.75f
private const val TrackShare = 0.15f
private const val OutlineShare = 0.2f

/** What is drawn on the amber accent of the dark themes. */
private val OnAmber = Color(0xFF2B1E00)

/**
 * The narration's colours in this reading theme: the reader's bars, with the theme's link colour as the accent.
 * The faint colours are the bars' text laid over their background, so that they are opaque.
 */
internal fun ThemeColours.narrationColors(): NarrationColors = NarrationColors(
    background = barBackground,
    foreground = barForeground,
    muted = lerp(barBackground, barForeground, MutedShare),
    accent = link,
    onAccent = if (isDark) OnAmber else Color.White,
    track = lerp(barBackground, barForeground, TrackShare),
    outline = lerp(barBackground, barForeground, OutlineShare),
)
