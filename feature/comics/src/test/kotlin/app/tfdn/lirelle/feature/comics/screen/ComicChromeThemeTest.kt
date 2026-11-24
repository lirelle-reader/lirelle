package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicBottomBarTag
import app.tfdn.lirelle.feature.comics.ComicTopBarTag
import app.tfdn.lirelle.feature.comics.darkUnderSystemBars
import app.tfdn.lirelle.feature.comics.page.colours
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The reader's bars in the app's theme, light or dark, and the tone of the system bars' icons over it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Phone)
class ComicChromeThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest()
    private var dark by mutableStateOf(false)

    /** The ink of whatever is around the reader, which its bars must not take: no colour of the app's. */
    private val around = Color.Red

    private fun PixelMap.pixels(): List<Color> =
        (0 until width).flatMap { x -> (0 until height).map { y -> this[x, y] } }

    private fun Color.isAround() = red > 0.7f && green < 0.3f && blue < 0.3f

    @Test
    fun `the bars take the theme the reader is shown in, and the ink of that theme, not the one around it`() {
        compose.setContent {
            LirelleTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalContentColor provides around) { screen.Content() }
            }
        }

        listOf(false, true).forEach { theme ->
            dark = theme
            compose.waitForIdle()
            listOf(ComicTopBarTag, ComicBottomBarTag).forEach { bar ->
                val pixels = compose.onNodeWithTag(bar).captureToImage().toPixelMap()
                // A corner of the bar, where nothing is drawn over its ground.
                val ground = pixels[1, pixels.height - 2].luminance()
                val inks = pixels.pixels()

                assertWithMessage("$bar, dark theme $theme: its ground").that(ground < 0.5f).isEqualTo(theme)
                assertWithMessage("$bar, dark theme $theme: ink taken from around the reader")
                    .that(inks.count { it.isAround() }).isEqualTo(0)
                // Something is written on it, and reads: far from its ground.
                assertWithMessage("$bar, dark theme $theme: ink that reads on its ground")
                    .that(inks.any { kotlin.math.abs(it.luminance() - ground) > 0.5f }).isTrue()
            }
        }
    }

    @Test
    fun `the system bars' icons take the tone of what lies under them, the bars or the ground of the pages`() {
        val lightBar = Color(0xFFFBF8F0)
        val darkBar = Color(0xFF14151C)
        val page = { background: Background -> background.colours().background }

        // The reader's bars are over the pages: the theme decides, whatever the ground of the pages.
        assertThat(darkUnderSystemBars(barsOver = true, bar = lightBar, page = page(Background.Black))).isFalse()
        assertThat(darkUnderSystemBars(barsOver = true, bar = darkBar, page = page(Background.White))).isTrue()
        // They are hidden: the ground of the pages decides, whatever the theme.
        assertThat(darkUnderSystemBars(barsOver = false, bar = lightBar, page = page(Background.Black))).isTrue()
        assertThat(darkUnderSystemBars(barsOver = false, bar = lightBar, page = page(Background.Grey))).isTrue()
        assertThat(darkUnderSystemBars(barsOver = false, bar = darkBar, page = page(Background.White))).isFalse()
    }
}
