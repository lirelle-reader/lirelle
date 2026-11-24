package app.tfdn.lirelle.feature.reader.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the Reading page overflows, is clipped or breaks mid-word: on a narrow phone, a phone, the pane
 * of a tablet and a wide window, in both languages, at the default and the largest text size. The last two
 * widths have the preview beside the controls, and the steppers and the ends two by two where they fit so.
 *
 * The preview's paragraph is the one text that is cut on purpose, after six lines, and that breaks its words
 * as a book does.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ReadingSettingsTextFitTest(private val language: String, width: Int, fontScale: Float) {

    // Tall, so that the page holds all of its content: nothing is judged while scrolled out of view.
    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h3600dp", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val french get() = language == "fr"

    /** The longest values: every stepper, every row and every line of the preview is as long as it gets. */
    private val longest = ReaderSettings(
        font = ReaderFont.Dyslexic,
        fontSizeStep = FontSizesPx.lastIndex,
        lineHeightStep = LineHeights.lastIndex,
        margins = MarginSize.Medium,
        align = ReaderAlign.Book,
        volumeKeys = true,
        header = RunningLine(RunningSlot.BookTitle, RunningSlot.PagesLeftInChapter),
        footer = RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent),
    )

    private fun show(settings: ReaderSettings, inPane: Boolean) {
        val host = PageHost(settings)
        compose.setContent {
            LirelleTheme { ReadingSettingsPage(host.settings, host.change, onBack = if (inPane) null else ({})) }
        }
    }

    private fun assertFits(): List<LaidOutText> {
        val texts = compose.assertAllTextFits(unbreakable = setOf(string(R.string.reader_preview_text)))
        val cut = texts.filter { it.isEllipsized && it.text != string(R.string.reader_preview_text) }
        assertWithMessage("texts cut short").that(cut).isEmpty()
        return texts
    }

    /** The texts reading [text]; there is at least one. */
    private fun List<LaidOutText>.reading(text: String): List<LaidOutText> {
        val found = filter { it.text == text }
        assertWithMessage("texts reading \"$text\"").that(found).isNotEmpty()
        return found
    }

    @Test
    fun `the page on its own, as it is at first`() {
        show(ReaderSettings(), inPane = false)

        val texts = assertFits()

        texts.reading(if (french) "Lecture" else "Reading")
        texts.reading(if (french) "Disposition et gestes" else "Layout and gestures")
        texts.reading(
            if (french) {
                "Aperçu approché\u00A0: le rendu exact dépend du livre."
            } else {
                "Approximate preview: the exact look depends on the book."
            },
        )
    }

    @Test
    fun `the page on its own, with the longest values`() {
        show(longest, inPane = false)

        val texts = assertFits()

        val margins = texts.reading(if (french) "Moyennes" else "Medium").first()
        assertWithMessage("lines of $margins").that(margins.lineCount).isEqualTo(1)
        texts.reading(if (french) "Mise en page du livre" else "Book's layout")
        texts.reading(if (french) "Vingt mille lieues sous les mers" else "The Time Machine")
    }

    @Test
    fun `the page in a pane, with the longest values`() {
        show(longest, inPane = true)

        assertFits()
    }

    @Test
    fun `the page at its other ends`() {
        val settings = ReaderSettings(
            fontSizeStep = 0,
            lineHeightStep = 0,
            margins = MarginSize.Wide,
            align = ReaderAlign.Justify,
            hyphenate = false,
        )
        show(settings, inPane = true)

        assertFits()
    }

    @Test
    fun `a list of choices open`() {
        show(longest, inPane = true)
        val end = if (french) "Pied de page, à gauche" else "Footer, left"
        compose.onNodeWithText(end).performScrollTo().performClick()

        val texts = assertFits()

        // In two rows and in the list under one of them.
        val name = if (french) "Pages restantes dans le chapitre" else "Pages left in the chapter"
        assertWithMessage("texts").that(texts.reading(name)).hasSize(3)
        texts.reading(if (french) "Pourcentage du chapitre" else "Percentage of the chapter")
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 860, 1280).flatMap { width ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf<Any>(language, width, fontScale) }
                }
            }
    }
}
