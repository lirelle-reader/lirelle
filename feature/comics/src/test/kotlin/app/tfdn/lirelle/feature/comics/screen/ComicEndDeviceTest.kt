package app.tfdn.lirelle.feature.comics.screen

import android.content.Context
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The row of the sheet at the end of a kept book, a PDF unless the test says another, for what the device keeps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicEndDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screen = ScreenUnderTest(openAlbum(spreadIndex = 47, barsShown = false).copy(kind = PagedKind.Pdf))
    private val kept = DeviceCopy(listOf(DownloadFormat.Text), 9_400_000, deleting = false, TextName.Pdf)

    private fun show(panel: ComicEndPanel) {
        screen.state = screen.state.copy(endPanel = panel)
        compose.setContent { screen.Content() }
        compose.waitForIdle()
    }

    private fun delete() = compose.onNodeWithText("Delete")

    private fun undo() = compose.onNodeWithText("Undo")

    @Test
    fun `a book the device does not keep has no row`() {
        show(ComicEndPanel(nextVolume = NextVolume(bookId = BookId("15"), title = "The Next One", seriesIndex = "3")))

        compose.onNodeWithText("On this device").assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().assertDoesNotExist()
    }

    @Test
    fun `the row says what the device keeps and how much, and delete asks for it`() {
        show(ComicEndPanel(device = kept))

        compose.onNodeWithText("On this device").assertExists()
        compose.onNodeWithText("PDF · ${context.fileSize(9_400_000)}").assertExists()
        undo().assertDoesNotExist()
        delete().performScrollTo().performClick()

        assertThat(screen.actions.calls).containsExactly("deleteFromDevice")
    }

    @Test
    fun `a kept comic and a kept DjVu have the row as a kept PDF has`() {
        var shown = false
        listOf(PagedKind.Comic, PagedKind.Djvu).forEach { kind ->
            screen.state = screen.state.copy(kind = kind, endPanel = ComicEndPanel(device = kept))
            if (!shown) compose.setContent { screen.Content() }
            shown = true
            compose.waitForIdle()

            compose.onNodeWithText("On this device").assertExists()
            compose.onNodeWithText("PDF · ${context.fileSize(9_400_000)}").assertExists()
            delete().performScrollTo().performClick()
        }

        assertThat(screen.actions.calls).containsExactly("deleteFromDevice", "deleteFromDevice")
    }

    @Test
    fun `a PDF kept with its audiobook names both`() {
        val both = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        show(ComicEndPanel(device = kept.copy(formats = both, textName = TextName.Pdf)))

        compose.onNodeWithText("PDF and audiobook · ${context.fileSize(9_400_000)}").assertExists()
    }

    @Test
    fun `the kept text is named by its file`() {
        val said = mapOf(
            TextName.Pdf to "PDF", TextName.Cbz to "CBZ", TextName.Cbr to "CBR", TextName.Cb7 to "CB7",
            TextName.Djvu to "DjVu",
        )
        var shown = false
        said.forEach { (name, word) ->
            screen.state = screen.state.copy(endPanel = ComicEndPanel(device = kept.copy(textName = name)))
            if (!shown) compose.setContent { screen.Content() }
            shown = true
            compose.waitForIdle()

            compose.onNodeWithText("$word · ${context.fileSize(9_400_000)}").assertExists()
        }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `a PDF kept with its audiobook, in French`() {
        val both = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        show(ComicEndPanel(device = kept.copy(formats = both, textName = TextName.Pdf)))

        compose.onNodeWithText("PDF et livre audio · ${context.fileSize(9_400_000)}").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the row, in French`() {
        show(ComicEndPanel(device = kept))

        compose.onNodeWithText("Sur cet appareil").assertExists()
        compose.onNodeWithText("PDF · ${context.fileSize(9_400_000)}").assertExists()
        compose.onNodeWithText("Supprimer").assertExists()

        screen.state = screen.state.copy(endPanel = ComicEndPanel(device = kept.copy(deleting = true)))
        compose.waitForIdle()

        compose.onNodeWithText("Supprimé de cet appareil").assertExists()
        compose.onNodeWithText("Annuler").assertExists()
    }

    @Test
    fun `a book that goes when it closes says so, politely, and undo keeps it`() {
        show(ComicEndPanel(device = kept.copy(deleting = true)))

        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNode(hasText("Deleted from this device") and polite).assertExists()
        compose.onNodeWithText("On this device").assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().performScrollTo().performClick()

        assertThat(screen.actions.calls).containsExactly("keepOnDevice")
    }

    @Test
    fun `the row waits with the rest while the rating is on its way`() {
        show(ComicEndPanel(rating = 4, sending = true, device = kept))

        delete().performScrollTo().assertIsNotEnabled().performClick()

        assertThat(screen.actions.calls).isEmpty()
    }

    @Test
    fun `its buttons are at least 48 dp on each side`() {
        show(ComicEndPanel(device = kept))
        delete().performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

        screen.state = screen.state.copy(endPanel = ComicEndPanel(device = kept.copy(deleting = true)))
        compose.waitForIdle()
        undo().performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
