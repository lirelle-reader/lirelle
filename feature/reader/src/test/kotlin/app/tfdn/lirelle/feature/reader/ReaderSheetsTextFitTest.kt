package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the reader's sheets overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ReaderSheetsTextFitTest(screen: String, private val language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val french get() = language == "fr"

    /** The longest values: every summary and every preview line is as long as it gets. */
    private val longest = ReaderSettings(
        fontSizeStep = FontSizesPx.lastIndex,
        margins = MarginSize.Medium,
        align = ReaderAlign.Book,
        volumeKeys = true,
        header = RunningLine(RunningSlot.BookTitle, RunningSlot.PagesLeftInChapter),
        footer = RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent),
    )

    @Test
    fun `the reading settings`() {
        val texts = show(ReaderSheet.Settings, longest)

        val margins = texts.whole(if (french) "Moyennes" else "Medium")
        assertWithMessage("lines of $margins").that(margins.lineCount).isEqualTo(1)
        texts.whole(if (french) "Mise en page du livre" else "Book's layout")
        texts.whole(if (french) "En-tête et pied de page" else "Header and footer")
    }

    @Test
    fun `the reading settings at their other ends`() {
        val settings = ReaderSettings(margins = MarginSize.Narrow, align = ReaderAlign.Justify, brightness = 1f)

        show(ReaderSheet.Settings, settings)
    }

    @Test
    fun `the header and footer settings, a list of choices open`() {
        show(ReaderSheet.RunningLines, longest)
        val end = if (french) "Pied de page, à gauche" else "Footer, left"
        compose.onNodeWithText(end).performScrollTo().performClick()

        val texts = compose.assertAllTextFits()

        // In the row and in the list under it.
        val name = if (french) "Pages restantes dans le chapitre" else "Pages left in the chapter"
        assertWithMessage("texts").that(texts.filter { it.text == name }).hasSize(3)
        texts.filter { it.text == name }.forEach {
            assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse()
        }
        val chapterPercent = texts.whole(if (french) "Pourcentage du chapitre" else "Percentage of the chapter")
        assertWithMessage("$chapterPercent is cut short").that(chapterPercent.isEllipsized).isFalse()
    }

    @Test
    fun `more options`() {
        val texts = show(ReaderSheet.MoreOptions, longest)

        texts.whole(if (french) "Défilement continu" else "Continuous scroll")
        texts.whole(if (french) "Désactivé" else "Off")
        texts.whole(if (french) "Notes de bas de page dans un panneau" else "Footnotes in a panel")
        texts.whole(if (french) "Toucher le coin pour marquer la page" else "Tap the corner to bookmark")
    }

    private fun show(sheet: ReaderSheet, settings: ReaderSettings): List<LaidOutText> {
        val host = SheetHost(sheet, settings)
        compose.setContent { LirelleTheme { ReaderSheets(host.state, host.actions) } }
        return compose.assertAllTextFits()
    }

    /** The texts reading [text], none of them cut short; there is at least one. */
    private fun List<LaidOutText>.whole(text: String): LaidOutText {
        val found = filter { it.text == text }
        assertWithMessage("texts reading \"$text\"").that(found).isNotEmpty()
        found.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
        return found.first()
    }

    companion object {
        // Tall, so that a sheet holds all of its content: nothing is judged while scrolled out of view.
        private const val Narrow = "w320dp-h3600dp"
        private const val Phone = "w411dp-h3600dp"
        private const val Wide = "w1280dp-h3600dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
