package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.feature.reader.bridge.PageBlend

private const val RgbMask = 0xFFFFFF

/**
 * The colours of one reading theme.
 *
 * @property background the page, [foreground] its text and [link] its links.
 * @property barBackground the reader's own bars and sheets, [barForeground] what is written on them.
 * @property highlightBlend how highlights mix with the text: multiplied on light pages, where the text
 * stays dark through them; plainly laid over dark ones, where multiplying would make them vanish.
 * @property highlightOpacity how opaque highlights are, from 0.1 to 1.
 * @property searchColour the outline of search results: none of the highlight colours, nor the links'.
 * @property ribbon the bookmark ribbon: the theme's accent.
 * @property isDark whether the page is dark, which the engine and the system bars need to know.
 */
data class ThemeColours(
    val background: Color,
    val foreground: Color,
    val link: Color,
    val barBackground: Color,
    val barForeground: Color,
    val isDark: Boolean,
    val highlightBlend: PageBlend,
    val highlightOpacity: Double,
    val searchColour: Color,
) {
    val ribbon: Color get() = link
}

// Highlights: the opacities are first guesses, to be settled on a device.
private const val LightHighlightOpacity = 0.4
private const val DarkHighlightOpacity = 0.35

// The app's own palette (night blue, amber, cream ink) carried into the page: paper rather than pure
// white, navy rather than grey, and amber links on the dark pages. Each link colour can also be read
// on its theme's bars, where the sheets use it as their accent.

private val Light = ThemeColours(
    background = Color(0xFFFBF8F1),
    foreground = Color(0xFF1B1E2E),
    link = Color(0xFF1D4FA8),
    barBackground = Color(0xFFEFEBE1),
    barForeground = Color(0xFF1B1E2E),
    isDark = false,
    highlightBlend = PageBlend.Multiply,
    highlightOpacity = LightHighlightOpacity,
    searchColour = Color(0xFFC2410C),
)

private val Sepia = ThemeColours(
    background = Color(0xFFF1E7D0),
    foreground = Color(0xFF3F3222),
    link = Color(0xFF7A4A00),
    barBackground = Color(0xFFE6D9BC),
    barForeground = Color(0xFF3F3222),
    isDark = false,
    highlightBlend = PageBlend.Multiply,
    highlightOpacity = LightHighlightOpacity,
    searchColour = Color(0xFFB91C1C),
)

private val Dark = ThemeColours(
    background = Color(0xFF12172B),
    foreground = Color(0xFFE2DDD1),
    link = Color(0xFFF2B544),
    barBackground = Color(0xFF1A203A),
    barForeground = Color(0xFFECE7DC),
    isDark = true,
    highlightBlend = PageBlend.Normal,
    highlightOpacity = DarkHighlightOpacity,
    searchColour = Color(0xFFFF6B35),
)

// Pure black for OLED screens, with a text dimmed a little so it does not glare in the dark.
private val Black = ThemeColours(
    background = Color(0xFF000000),
    foreground = Color(0xFFB9B5AB),
    link = Color(0xFFD6A041),
    barBackground = Color(0xFF0B0D14),
    barForeground = Color(0xFFC9C5BB),
    isDark = true,
    highlightBlend = PageBlend.Normal,
    highlightOpacity = DarkHighlightOpacity,
    searchColour = Color(0xFFFF6B35),
)

/** The colours of this theme; [ReaderTheme.System] is light or dark with the device. */
fun ReaderTheme.colours(systemDark: Boolean): ThemeColours = when (this) {
    ReaderTheme.System -> if (systemDark) Dark else Light
    ReaderTheme.Light -> Light
    ReaderTheme.Sepia -> Sepia
    ReaderTheme.Dark -> Dark
    ReaderTheme.Black -> Black
}

/** As the page wants it: `#rrggbb`, the transparency left out. */
fun Color.toCssHex(): String = "#%06x".format(toArgb() and RgbMask)
