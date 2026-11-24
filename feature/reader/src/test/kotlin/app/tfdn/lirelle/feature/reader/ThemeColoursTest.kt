package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.feature.reader.bridge.PageBlend
import kotlin.math.abs
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ThemeColoursTest {

    private fun contrast(one: Color, other: Color): Float {
        val lighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private val all = ReaderTheme.entries.flatMap { theme ->
        listOf(false, true).map { dark -> "$theme (system dark: $dark)" to theme.colours(dark) }
    }

    @Test
    fun `the text stands out on the page by at least 7 to 1 in every theme`() {
        all.forEach { (name, colours) ->
            assertWithMessage(name).that(contrast(colours.foreground, colours.background)).isAtLeast(7f)
        }
    }

    @Test
    fun `the bars' text stands out on the bars by at least 7 to 1 in every theme`() {
        all.forEach { (name, colours) ->
            assertWithMessage(name).that(contrast(colours.barForeground, colours.barBackground)).isAtLeast(7f)
        }
    }

    @Test
    fun `links can be read on the page in every theme`() {
        all.forEach { (name, colours) ->
            assertWithMessage(name).that(contrast(colours.link, colours.background)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `links can be read on the bars too, where the sheets use them as their accent`() {
        all.forEach { (name, colours) ->
            assertWithMessage(name).that(contrast(colours.link, colours.barBackground)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `the black theme is pure black, for screens that switch their pixels off`() {
        assertThat(ReaderTheme.Black.colours(systemDark = false).background).isEqualTo(Color.Black)
    }

    @Test
    fun `a theme says whether its page is dark`() {
        all.forEach { (name, colours) ->
            assertWithMessage(name).that(colours.isDark).isEqualTo(colours.background.luminance() < 0.5f)
        }
    }

    @Test
    fun `following the system is the light theme by day and the dark one by night`() {
        assertThat(ReaderTheme.System.colours(systemDark = false)).isEqualTo(ReaderTheme.Light.colours(false))
        assertThat(ReaderTheme.System.colours(systemDark = true)).isEqualTo(ReaderTheme.Dark.colours(false))
    }

    @Test
    fun `the other themes do not follow the system`() {
        (ReaderTheme.entries - ReaderTheme.System).forEach {
            assertThat(it.colours(systemDark = true)).isEqualTo(it.colours(systemDark = false))
        }
    }

    @Test
    fun `a colour is written for the page without its transparency`() {
        assertThat(Color(0xFF0B57D0).toCssHex()).isEqualTo("#0b57d0")
        assertThat(Color(0x80000000).toCssHex()).isEqualTo("#000000")
        assertThat(Color.White.toCssHex()).isEqualTo("#ffffff")
    }

    private val highlightColours = listOf(0xFFFACC15, 0xFF4ADE80, 0xFF38BDF8, 0xFFF472B6).map { Color(it) }

    private fun distance(one: Color, other: Color) =
        abs(one.red - other.red) + abs(one.green - other.green) +
            abs(one.blue - other.blue)

    @Test
    fun `highlights multiply on the light pages and blend normally on the dark ones`() {
        all.forEach { (name, colours) ->
            val expected = if (colours.isDark) PageBlend.Normal else PageBlend.Multiply
            assertWithMessage(name).that(colours.highlightBlend).isEqualTo(expected)
            assertWithMessage(name).that(colours.highlightOpacity).isAtLeast(0.1)
            assertWithMessage(name).that(colours.highlightOpacity).isAtMost(1.0)
        }
    }

    @Test
    fun `the search colour is none of the highlight colours nor the link colour, and shows on the page`() {
        all.forEach { (name, colours) ->
            (highlightColours + colours.link).forEach {
                assertWithMessage("$name vs $it").that(distance(colours.searchColour, it)).isAtLeast(0.3f)
            }
            assertWithMessage(name).that(contrast(colours.searchColour, colours.background)).isAtLeast(3f)
        }
    }
}
