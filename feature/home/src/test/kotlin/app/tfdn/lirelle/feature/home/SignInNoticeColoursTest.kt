package app.tfdn.lirelle.feature.home

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What WCAG asks of text against its ground, for the level AAA: more than the notice needs, and what it has. */
private const val EnhancedContrast = 7.0

private fun contrast(first: Color, second: Color): Double {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.near(other: Color) =
    abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f

/** A ground, away from the rounded corners and from anything written: at mid-height, a little in from the start. */
private fun PixelMap.groundAtStart() = this[width / 24, height / 2]

private fun PixelMap.has(colour: Color) =
    (0 until width).any { x -> (0 until height).any { y -> this[x, y].near(colour) } }

/**
 * The notice is drawn with one pair of colours, used both ways round: its text on the card, and the card's
 * colour as the text of its button, whose ground is the colour of the card's text. Checked on what is drawn,
 * in both themes: a notice drawn with other colours fails here, whatever the theme's pair is worth.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Pixels are drawn: the default mode draws none.
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SignInNoticeColoursTest {

    @get:Rule
    val compose = createComposeRule()

    private var dark by mutableStateOf(false)
    private lateinit var scheme: ColorScheme

    private fun drawn(tag: String): PixelMap {
        compose.waitForIdle()
        return compose.onNodeWithTag(tag).captureToImage().toPixelMap()
    }

    @Test
    fun `the card and its button are drawn with the error container's pair, each the other way round`() {
        compose.setContent {
            LirelleTheme(darkTheme = dark) {
                scheme = MaterialTheme.colorScheme
                Surface {
                    SignInNotice(ExpiredSession("p7", "camille", ServerKind.BookOrbit), onSignInAgain = {})
                }
            }
        }

        listOf(false, true).forEach { theme ->
            dark = theme
            val card = drawn(SignInNoticeTag)
            val button = drawn(SignInAgainTag)

            assertWithMessage("dark $theme: the card's ground")
                .that(card.groundAtStart().near(scheme.errorContainer)).isTrue()
            assertWithMessage("dark $theme: the card's text").that(card.has(scheme.onErrorContainer)).isTrue()
            assertWithMessage("dark $theme: the button's ground")
                .that(button.groundAtStart().near(scheme.onErrorContainer)).isTrue()
            assertWithMessage("dark $theme: the button's label").that(button.has(scheme.errorContainer)).isTrue()
            // And that pair reads, which is why it is the one used.
            assertWithMessage("dark $theme: contrast of the pair")
                .that(contrast(scheme.onErrorContainer, scheme.errorContainer)).isAtLeast(EnhancedContrast)
        }
    }
}
