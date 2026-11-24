package app.tfdn.lirelle.feature.settings.appearance

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the Appearance page overflows, is clipped or breaks mid-word, on a narrow phone, a phone and a
 * tablet, in both languages, at the default and the largest text size. The names of the three tiles are whole:
 * where they cannot be side by side, the tiles stack.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class AppearanceTextFitTest(
    language: String,
    private val width: Int,
    private val fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun show(theme: AppTheme, onBack: (() -> Unit)?) {
        compose.setContent {
            LirelleTheme { AppearancePage(theme = theme, onChoose = {}, onBack = onBack) }
        }
    }

    private fun tiles(): List<Rect> =
        AppTheme.entries.map { compose.onNodeWithTag(themeTileTag(it)).fetchSemanticsNode().boundsInRoot }

    @Test
    fun `the page fits and no name is cut short`() {
        show(AppTheme.System, onBack = {})

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    @Test
    fun `the page fits with the longest sentence`() {
        show(AppTheme.Dark, onBack = {})

        compose.assertAllTextFits()
    }

    @Test
    fun `the page fits in a pane`() {
        show(AppTheme.Light, onBack = null)

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    @Test
    fun `the tiles are on one line wherever their names fit, and stack otherwise`() {
        show(AppTheme.System, onBack = {})

        val tiles = tiles()

        // In both languages the names are too long for one line only on the narrow phone at the largest size.
        if (width == 320 && fontScale == 2.0f) {
            // Stacked: one under the other, each on the whole width.
            assertThat(tiles.map { it.left }.distinct()).hasSize(1)
            assertThat(tiles.zipWithNext().all { (above, below) -> below.top >= above.bottom }).isTrue()
        } else {
            assertThat(tiles.map { it.top }.distinct()).hasSize(1)
            // Of equal width, to a pixel of rounding.
            assertThat(tiles.maxOf { it.width } - tiles.minOf { it.width }).isAtMost(1f)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 1280).flatMap { width ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(language, width, fontScale) }
                }
            }
    }
}
