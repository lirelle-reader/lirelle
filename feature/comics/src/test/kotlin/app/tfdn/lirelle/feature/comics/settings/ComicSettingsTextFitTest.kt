package app.tfdn.lirelle.feature.comics.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** The least contrast of a text with what is behind it, for a text of any size to read well. */
private const val ReadableContrast = 4.5f

/**
 * No text of the Comics page overflows, is clipped or breaks mid-word, on a narrow phone, a phone and a tablet,
 * in both languages, at the default and the largest text size. The page is drawn in the app's theme, light or
 * dark, where the reader's sheet is always dark: its texts read well in both.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ComicSettingsTextFitTest(
    language: String,
    width: Int,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val settings = ComicSettings(fit = Fit.Width, twoPages = TwoPages.Automatic, background = Background.Grey)
    private val nothing = object : ComicSettingsChanges {
        override fun setFit(fit: Fit) = Unit

        override fun setTwoPages(twoPages: TwoPages) = Unit

        override fun setBackground(background: Background) = Unit

        override fun setWholePageFirst(first: Boolean) = Unit
    }

    /** The group of reading panel by panel: its name, its switch and what is said under it, in both languages. */
    private val panelTexts = setOf(
        "Panel by panel",
        "Whole page first",
        "Each page is shown whole before its first panel.",
        "Panels are found automatically. A page that does not lend itself to it is shown whole.",
        "Case par case",
        "Planche entière d’abord",
        "Chaque planche s’affiche en entier avant sa première case.",
        "Les cases sont repérées automatiquement. Une planche qui ne s’y prête pas s’affiche entière.",
    )

    private fun show(dark: Boolean, onBack: (() -> Unit)?): ColorScheme {
        lateinit var colours: ColorScheme
        compose.setContent {
            LirelleTheme(darkTheme = dark) {
                colours = MaterialTheme.colorScheme
                ComicSettingsPage(settings, nothing, onBack)
            }
        }
        compose.waitForIdle()
        return colours
    }

    @Test
    fun `the page fits in the light theme, and reads well there`() {
        val colours = show(dark = false, onBack = {})

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
        assertReadable(colours)
    }

    @Test
    fun `the page fits in the dark theme, and reads well there`() {
        val colours = show(dark = true, onBack = {})

        compose.assertAllTextFits()
        assertReadable(colours)
    }

    @Test
    fun `the group of the panels is on the page, whole`() {
        show(dark = false, onBack = {})

        val texts = compose.assertAllTextFits().map { it.text }

        assertThat(texts.filter { it in panelTexts }).hasSize(4)
    }

    @Test
    fun `the page fits in a pane`() {
        show(dark = false, onBack = null)

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    /** Each text of the page on what it is drawn over: the groups' names, the choices, the notes. */
    private fun assertReadable(colours: ColorScheme) {
        val pairs = mapOf(
            "the name of a group" to (colours.primary to colours.surface),
            "a choice" to (colours.onSurface to colours.surfaceContainerHighest),
            "the chosen choice" to (colours.onPrimary to colours.primary),
            "a note" to (colours.onSurfaceVariant to colours.surface),
            "the title of the direction's note" to (colours.onSurface to colours.surfaceContainer),
            "the direction's note" to (colours.onSurfaceVariant to colours.surfaceContainer),
        )
        pairs.forEach { (what, pair) ->
            assertWithMessage("contrast of $what").that(contrast(pair.first, pair.second)).isAtLeast(ReadableContrast)
        }
    }

    private fun contrast(text: Color, behind: Color): Float {
        val lighter = maxOf(text.luminance(), behind.luminance())
        val darker = minOf(text.luminance(), behind.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
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
