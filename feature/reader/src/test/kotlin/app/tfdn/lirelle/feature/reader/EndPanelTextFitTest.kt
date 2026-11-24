package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
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

/** No text of the panel at the end of the book overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class EndPanelTextFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val next = NextVolume(bookId = BookId("13"), title = LongNextTitle, seriesIndex = "12.5")

    /** Every format, the longest phrase, and a size of four digits. */
    private val kept = DeviceCopy(DownloadFormat.entries, bytes = 1_234_567_890_123, deleting = false)

    @Test
    fun `the panel with everything in it, after a failure`() {
        val texts = show(EndPanel(rating = 3, nextVolume = next, failed = true, device = kept))

        // The book's title takes the lines it needs; the next volume's keeps to one.
        val title = texts.single { it.text == LongTitle }
        assertWithMessage("$title is cut short").that(title.isEllipsized).isFalse()
        val volume = texts.single { it.text == LongNextTitle }
        assertWithMessage("lines of $volume").that(volume.lineCount).isEqualTo(1)
        assertWithMessage("$volume is cut short").that(volume.isEllipsized).isTrue()
    }

    @Test
    fun `the panel while the rating is on its way`() {
        show(EndPanel(rating = 5, nextVolume = next, sending = true, device = kept))
    }

    @Test
    fun `the panel of a book that goes from the device when it closes`() {
        show(EndPanel(nextVolume = next, device = kept.copy(deleting = true)))
    }

    @Test
    fun `the panel of a text read by pages kept with everything else`() {
        val document = kept.copy(textName = TextName.Djvu)

        show(EndPanel(rating = 3, nextVolume = next, failed = true, device = document))
    }

    @Test
    fun `the panel of a book alone`() {
        show(EndPanel())
    }

    private fun show(panel: EndPanel): List<LaidOutText> {
        compose.setContent {
            LirelleTheme {
                EndPanelSheet(panel, title = LongTitle, ReaderTheme.Dark.colours(systemDark = true), NoActions)
            }
        }
        // The next volume's title is the one text meant to be cut: one line, then an ellipsis.
        return compose.assertAllTextFits(unbreakable = setOf(LongNextTitle))
    }

    private object NoActions : EndPanelActions {
        override fun setRating(stars: Int?) = Unit

        override fun closeEndPanel() = Unit

        override fun openNextVolume() = Unit

        override fun dismissEndPanel() = Unit

        override fun deleteFromDevice() = Unit

        override fun keepOnDevice() = Unit
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val LongTitle =
            "A title long enough to need several lines on a narrow phone at a large text size, and then some more"
        private const val LongNextTitle =
            "The following volume, whose title goes on and on well past the width of any sheet, on any screen, " +
                "because its publisher could not choose between a title and a summary"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
