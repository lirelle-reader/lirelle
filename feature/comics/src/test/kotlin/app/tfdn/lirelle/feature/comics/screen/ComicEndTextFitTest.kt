package app.tfdn.lirelle.feature.comics.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongTitle =
    "Un titre d’album assez long pour prendre plusieurs lignes sur un téléphone étroit, en grands caractères"
private const val LongNextTitle =
    "The following album, whose title goes on and on well past the width of any sheet, on any screen, " +
        "because its publisher could not choose between a title and a summary"

/** No text of the sheet at the end of an album overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ComicEndTextFitTest(screenSize: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screenSize", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val next = NextVolume(bookId = BookId("15"), title = LongNextTitle, seriesIndex = "12.5")

    /** Every format, of the largest size there is a unit for: the longest the row can say. */
    private val kept = DeviceCopy(DownloadFormat.entries, bytes = 1_234_567_890_123, deleting = false)

    @Test
    fun `the sheet with everything in it, after a failure`() {
        val texts = show(ComicEndPanel(rating = 3, nextVolume = next, failed = true))

        // The album's title takes the lines it needs; the next one's keeps to one.
        val title = texts.single { it.text == LongTitle }
        assertWithMessage("$title is cut short").that(title.isEllipsized).isFalse()
        val volume = texts.single { it.text == LongNextTitle }
        assertWithMessage("lines of $volume").that(volume.lineCount).isEqualTo(1)
        assertWithMessage("$volume is cut short").that(volume.isEllipsized).isTrue()
    }

    @Test
    fun `the sheet while the rating is on its way`() {
        show(ComicEndPanel(rating = 5, nextVolume = next, sending = true))
    }

    @Test
    fun `the sheet of an album alone`() {
        show(ComicEndPanel())
    }

    @Test
    fun `the sheet of a book that is not a comic, with everything in it`() {
        show(ComicEndPanel(rating = 3, nextVolume = next, failed = true, device = kept), PagedKind.Pdf)
    }

    @Test
    fun `the sheet of a kept DjVu and of a kept comic, named by their files`() {
        val document = kept.copy(textName = TextName.Djvu)
        show(ComicEndPanel(rating = 3, nextVolume = next, failed = true, device = document), PagedKind.Djvu)
    }

    @Test
    fun `the sheet of a kept PDF while the rating is on its way`() {
        show(ComicEndPanel(rating = 5, nextVolume = next, sending = true, device = kept), PagedKind.Pdf)
    }

    @Test
    fun `the sheet of a PDF that goes from the device when it closes`() {
        show(ComicEndPanel(nextVolume = next, device = kept.copy(deleting = true)), PagedKind.Pdf)
    }

    private fun show(panel: ComicEndPanel, kind: PagedKind = PagedKind.Comic): List<LaidOutText> {
        val album = openAlbum(spreadIndex = 47, barsShown = false)
            .copy(title = LongTitle, kind = kind, endPanel = panel)
        val screen = ScreenUnderTest(album)
        compose.setContent { screen.Content() }
        compose.waitForIdle()
        // The next album's title is the one text meant to be cut: one line, then an ellipsis.
        return compose.assertAllTextFits(unbreakable = setOf(LongNextTitle))
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = deviceCases()
    }
}
