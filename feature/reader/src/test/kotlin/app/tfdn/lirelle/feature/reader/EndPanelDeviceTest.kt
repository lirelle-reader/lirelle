package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The row of the panel at the end of the book for what the device keeps of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EndPanelDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()

    private val actions = object : EndPanelActions {
        override fun setRating(stars: Int?) = Unit

        override fun closeEndPanel() = Unit

        override fun openNextVolume() = Unit

        override fun dismissEndPanel() = Unit

        override fun deleteFromDevice() {
            asked += "delete"
        }

        override fun keepOnDevice() {
            asked += "keep"
        }
    }

    private val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
    private val kept = DeviceCopy(formats, 1_200_000_000, deleting = false)

    private fun show(panel: EndPanel) {
        val colours = ReaderTheme.Sepia.colours(systemDark = false)
        compose.setContent {
            LirelleTheme { EndPanelSheet(panel, title = "The Green Lighthouse", colours, actions) }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun delete() = compose.onNodeWithText(text(R.string.reader_end_device_delete))

    private fun undo() = compose.onNodeWithText(text(R.string.reader_end_device_undo))

    @Test
    fun `a book the device does not keep has no row`() {
        show(EndPanel(nextVolume = NextVolume(bookId = BookId("13"), title = "The Next One", seriesIndex = "3")))

        compose.onNodeWithText(text(R.string.reader_end_device)).assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().assertDoesNotExist()
    }

    @Test
    fun `the row says what the device keeps and how much, and delete asks for it`() {
        show(EndPanel(device = kept))

        compose.onNodeWithText(text(R.string.reader_end_device)).assertIsDisplayed()
        compose.onNodeWithText("Text and audiobook · ${context.fileSize(1_200_000_000)}").assertIsDisplayed()
        undo().assertDoesNotExist()
        delete().performClick()

        assertThat(asked).containsExactly("delete")
    }

    @Test
    fun `one format is named alone`() {
        show(EndPanel(device = DeviceCopy(listOf(DownloadFormat.Narration), 52_000_000, deleting = false)))

        compose.onNodeWithText("Narration · ${context.fileSize(52_000_000)}").assertIsDisplayed()
    }

    @Test
    fun `a text read by pages is named by its file, alone and beside an audiobook`() {
        var copy by mutableStateOf(kept)
        val colours = ReaderTheme.Sepia.colours(systemDark = false)
        compose.setContent {
            LirelleTheme { EndPanelSheet(EndPanel(device = copy), title = "The Green Lighthouse", colours, actions) }
        }
        val size = context.fileSize(1_200_000_000)
        val said = mapOf(
            TextName.Pdf to "PDF", TextName.Cbz to "CBZ", TextName.Cbr to "CBR", TextName.Cb7 to "CB7",
            TextName.Djvu to "DjVu", TextName.Text to "Text",
        )

        said.forEach { (name, word) ->
            copy = kept.copy(formats = listOf(DownloadFormat.Text), textName = name)
            compose.onNodeWithText("$word · $size").assertIsDisplayed()
            copy = kept.copy(textName = name)
            compose.onNodeWithText("$word and audiobook · $size").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `a PDF kept with its audiobook, in French`() {
        show(EndPanel(device = kept.copy(textName = TextName.Pdf)))

        compose.onNodeWithText("PDF et livre audio · ${context.fileSize(1_200_000_000)}").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the row, in French`() {
        show(EndPanel(device = kept.copy(formats = DownloadFormat.entries)))

        compose.onNodeWithText("Sur cet appareil").assertIsDisplayed()
        compose.onNodeWithText("Texte, livre audio et narration · ${context.fileSize(1_200_000_000)}")
            .assertIsDisplayed()
        compose.onNodeWithText("Supprimer").assertIsDisplayed()
    }

    @Test
    fun `a book that goes when it closes says so, and undo keeps it`() {
        show(EndPanel(device = kept.copy(deleting = true)))

        compose.onNodeWithText(text(R.string.reader_end_device_deleted)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_end_device)).assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().performClick()

        assertThat(asked).containsExactly("keep")
    }

    @Test
    fun `the row waits with the rest while the rating is on its way`() {
        show(EndPanel(rating = 4, sending = true, device = kept))

        delete().assertIsNotEnabled().performClick()

        assertThat(asked).isEmpty()
    }

    @Test
    fun `its buttons are at least 48 dp on each side`() {
        show(EndPanel(device = kept))

        val bounds = delete().performScrollTo().assertIsEnabled().getUnclippedBoundsInRoot()
        assertThat(bounds.width.value).isAtLeast(MinTouchDp)
        assertThat(bounds.height.value).isAtLeast(MinTouchDp)
    }

    private companion object {
        const val MinTouchDp = 48f
    }
}
