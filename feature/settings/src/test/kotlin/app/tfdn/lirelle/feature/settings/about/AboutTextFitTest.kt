package app.tfdn.lirelle.feature.settings.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val Version = "0.1.1234"
private const val LargestFontScale = 2.0f

private const val GplOpening = "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\n" +
    "Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\n" +
    "Everyone is permitted to copy and distribute verbatim copies of this license document, but changing it is " +
    "not allowed."

/** The long-named components, with the app and the first lines of the GPL. */
private fun licencesWithTheApp(): Licences =
    longNamedLicences().copy(app = appComponent(licenceText = GplOpening))

/** About with the long-named components, at a width class. */
@Composable
private fun About(widthClass: WindowWidthClass) {
    LirelleTheme {
        CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
            AboutContent(appVersion = Version, licences = licencesWithTheApp(), onBack = {})
        }
    }
}

/** The names that may break anywhere: at the largest text size, those with a word longer than a line. */
private fun namesFreeToBreak(fontScale: Float): Set<String> =
    if (fontScale == LargestFontScale) NamesWithAVeryLongWord else emptySet()

/** The name made like coordinates, as it is drawn: with its places to break. */
private val CoordinatesShown = CoordinatesName.withBreakPlaces()

/** Nothing is cut short, whatever may break. */
private fun assertNothingCutShort(texts: List<LaidOutText>) {
    assertWithMessage("texts cut short").that(texts.filter { it.isEllipsized }).isEmpty()
}

/**
 * No text of About, of the licences' list or of a licence's text overflows, is clipped or breaks mid-word, on a
 * narrow phone, a phone and a tablet, in both languages, at the default and the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class AboutTextFitTest(private val language: String, width: Int, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `About fits`() {
        compose.setContent { About(WindowWidthClass.Compact) }

        assertNothingCutShort(compose.assertAllTextFits())
    }

    @Test
    fun `the licences' list fits, long names wrapping`() {
        compose.setContent { About(WindowWidthClass.Compact) }

        compose.onNodeWithTag(AboutLicencesRowTag).performClick()

        val texts = compose.assertAllTextFits(unbreakable = namesFreeToBreak(fontScale))
        assertNothingCutShort(texts)
        // The rows are there, with their names and chips.
        assertThat(texts.map { it.text }).containsAtLeast("Atkinson Hyperlegible", "OFL-1.1", CoordinatesShown)
    }

    @Test
    fun `a licence's text fits under a long name`() {
        compose.setContent { About(WindowWidthClass.Compact) }
        compose.onNodeWithTag(AboutLicencesRowTag).performClick()

        compose.onNodeWithTag(licenceRowTag(CoordinatesId)).performClick()

        val texts = compose.assertAllTextFits()
        assertNothingCutShort(texts)
        assertThat(texts.map { it.text }).containsAtLeast(CoordinatesShown, "Apache License 2.0")
    }

    @Test
    fun `the app's licence fits, its notice in the reader's language`() {
        compose.setContent { About(WindowWidthClass.Compact) }

        compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()

        val texts = compose.assertAllTextFits()
        assertNothingCutShort(texts)
        val shown = texts.map { it.text }
        assertThat(shown).containsAtLeast("GNU General Public License v3.0 or later", GplOpening)
        val french = shown.any { it.startsWith("Lirelle est un logiciel libre") }
        val english = shown.any { it.startsWith("Lirelle is free software") }
        assertThat(french to english).isEqualTo((language == "fr") to (language == "en"))
        assertThat(shown.any { it.contains("seule version qui fait foi") }).isEqualTo(language == "fr")
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 1280).flatMap { width ->
                    listOf(1.0f, LargestFontScale).map { fontScale -> arrayOf(language, width, fontScale) }
                }
            }
    }
}

/**
 * No text of the two panes of the wide layout overflows, is clipped or breaks mid-word, at 1280 dp, in both
 * languages, at the default and the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class AboutWideTextFitTest(language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w1280dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `the two panes fit, the licences beside About`() {
        compose.setContent { About(WindowWidthClass.Expanded) }

        val texts = compose.assertAllTextFits(unbreakable = namesFreeToBreak(fontScale))

        assertNothingCutShort(texts)
        // Both panes are there: the version, and the rows.
        assertThat(texts.map { it.text }).containsAtLeast(Version, "Atkinson Hyperlegible", CoordinatesShown)
    }

    @Test
    fun `the two panes fit with a licence's text`() {
        compose.setContent { About(WindowWidthClass.Expanded) }

        compose.onNodeWithTag(licenceRowTag(CoordinatesId)).performClick()

        val texts = compose.assertAllTextFits()
        assertNothingCutShort(texts)
        assertThat(texts.map { it.text }).containsAtLeast(Version, CoordinatesShown, "Apache License 2.0")
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, font scale {1}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, LargestFontScale).map { fontScale -> arrayOf<Any>(language, fontScale) }
            }
    }
}
