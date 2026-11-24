package app.tfdn.lirelle.feature.reader.narration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.feature.reader.colours
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The narration's bar, strip and panel in the colours of each reading theme. */
class ReaderNarrationColorsTest {

    private fun contrast(one: Color, other: Color): Float {
        val lighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private val themes = listOf(ReaderTheme.Light, ReaderTheme.Sepia, ReaderTheme.Dark, ReaderTheme.Black)
        .map { it to it.colours(systemDark = false) }

    @Test
    fun `the bar is the reader's bar, its accent the theme's links`() {
        themes.forEach { (theme, colours) ->
            val narration = colours.narrationColors()

            assertWithMessage("$theme background").that(narration.background).isEqualTo(colours.barBackground)
            assertWithMessage("$theme foreground").that(narration.foreground).isEqualTo(colours.barForeground)
            assertWithMessage("$theme accent").that(narration.accent).isEqualTo(colours.link)
        }
    }

    @Test
    fun `secondary text and what is drawn on the accent can be read, by at least 4,5 to 1`() {
        themes.forEach { (theme, colours) ->
            val narration = colours.narrationColors()

            assertWithMessage("$theme muted").that(contrast(narration.muted, narration.background)).isAtLeast(4.5f)
            assertWithMessage("$theme on accent").that(contrast(narration.onAccent, narration.accent)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `on the accent, white on light themes and a dark amber on dark ones`() {
        assertThat(ReaderTheme.Light.colours(false).narrationColors().onAccent).isEqualTo(Color.White)
        assertThat(ReaderTheme.Sepia.colours(false).narrationColors().onAccent).isEqualTo(Color.White)
        assertThat(ReaderTheme.Dark.colours(false).narrationColors().onAccent).isEqualTo(Color(0xFF2B1E00))
        assertThat(ReaderTheme.Black.colours(false).narrationColors().onAccent).isEqualTo(Color(0xFF2B1E00))
    }

    @Test
    fun `the track and the outline are faint, the outline a little less`() {
        themes.forEach { (theme, colours) ->
            val narration = colours.narrationColors()
            val track = contrast(narration.track, narration.background)
            val outline = contrast(narration.outline, narration.background)
            val muted = contrast(narration.muted, narration.background)

            assertWithMessage("$theme track").that(track).isGreaterThan(1f)
            assertWithMessage("$theme outline").that(outline).isGreaterThan(track)
            assertWithMessage("$theme outline").that(outline).isLessThan(muted)
        }
    }
}
