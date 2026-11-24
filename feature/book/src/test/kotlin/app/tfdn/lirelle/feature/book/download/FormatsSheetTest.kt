package app.tfdn.lirelle.feature.book.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.book.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FormatsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val applied = mutableListOf<SheetPlan>()
    private var cancels = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun size(bytes: Long) = context.fileSize(bytes)

    private fun show(situation: DownloadSituation, waitsForWifi: Boolean = false) {
        compose.setContent {
            LirelleTheme {
                FormatsSheetContent(
                    title = "A Complete Book",
                    sheet = FormatsSheetState(situation, freeBytes = 41_200 * Mb, waitsForWifi = waitsForWifi),
                    onCancel = { cancels++ },
                    onApply = { applied += it },
                )
            }
        }
    }

    private fun row(format: DownloadFormat) = compose.onNodeWithTag(formatRowTag(format)).performScrollTo()

    private fun apply() = compose.onNodeWithTag(FormatsApplyTag).performScrollTo()

    /** Each of [texts] is somewhere on the sheet; two formats may have the same size once rounded. */
    private fun assertShown(vararg texts: String) = texts.forEach {
        assertWithMessage(it).that(compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `three formats are three rows to check, with what they are for and their sizes`() {
        show(situation(offered = ThreeFormats))

        listOf(Text, Audiobook, Narration).forEach { row(it).assertIsOff().assertIsEnabled() }
        assertShown(
            text(R.string.download_sheet_title),
            text(R.string.download_sheet_subtitle, "A Complete Book"),
            text(R.string.download_sheet_text_in_narration),
            context.resources.getQuantityString(R.plurals.download_sheet_audiobook_chapters, 139, 139),
            text(R.string.download_sheet_narration),
            size(1_070 * Mb),
            size(1_080 * Mb),
            text(R.string.download_sheet_free, size(41_200 * Mb)),
        )
        compose.onAllNodesWithText(text(R.string.download_sheet_metered)).assertCountEquals(0)
        // Nothing to do yet.
        apply().assertIsNotEnabled()
    }

    @Test
    fun `the text of a read-along file says about how large it is`() {
        show(situation(offered = listOf(EstimatedText) + ThreeFormats.drop(1)))

        assertShown(text(R.string.download_sheet_size_estimated, size(3 * Mb)))
        assertThat(text(R.string.download_sheet_size_estimated, "3.00 MB")).isEqualTo("≈ 3.00 MB")
    }

    @Test
    fun `one format is one row`() {
        show(situation(offered = TextAndAudiobook.take(1)))

        row(Text).assertIsOff()
        assertShown(text(R.string.download_sheet_text))
        compose.onAllNodesWithTag(formatRowTag(Audiobook)).assertCountEquals(0)
    }

    @Test
    fun `the narration checks the text and locks it, and the button says what it downloads`() {
        show(situation(offered = ThreeFormats))

        row(Narration).performClick()

        row(Narration).assertIsOn()
        row(Text).assertIsOn().assertIsNotEnabled()
        apply().assertIsEnabled().performClick()
        assertThat(applied)
            .containsExactly(SheetPlan(toDownload = setOf(Text, Narration), downloadBytes = 1_080 * Mb))
    }

    /** What the row of [format] is named. */
    private fun name(format: DownloadFormat, word: String) = compose.onNode(
        hasText(word) and hasAnyAncestor(hasTestTag(formatRowTag(format))),
        useUnmergedTree = true,
    )

    @Test
    fun `the text of a book read by pages is a row named by its file`() {
        val names = mapOf(
            TextName.Pdf to "PDF", TextName.Cbz to "CBZ", TextName.Cbr to "CBR", TextName.Cb7 to "CB7",
            TextName.Djvu to "DjVu", TextName.Text to "Text",
        )
        var offered by mutableStateOf(PdfAndAudiobook)
        compose.setContent {
            LirelleTheme {
                val sheet = FormatsSheetState(situation(offered = offered), 41_200 * Mb, waitsForWifi = false)
                FormatsSheetContent(title = "A Complete Book", sheet = sheet, onCancel = {}, onApply = {})
            }
        }

        names.forEach { (textName, word) ->
            offered = listOf(PdfAndAudiobook[0].copy(textName = textName), PdfAndAudiobook[1])

            row(Text)
            name(Text, word).assertExists()
            name(Audiobook, text(R.string.download_format_audiobook)).assertExists()
            if (word != "Text") name(Text, "Text").assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the rows in French, a reflowable text as it was`() {
        show(situation())
        row(Text)
        name(Text, "Texte").assertExists()

        row(Audiobook)
        name(Audiobook, "Livre audio").assertExists()
    }

    @Test
    fun `deleting a kept PDF and its audiobook names both before it is done`() {
        val kept = downloaded(complete(Text, 9 * Mb).copy(textName = TextName.Pdf), complete(Audiobook, 982 * Mb))
        show(situation(offered = PdfAndAudiobook, book = kept))
        row(Text).performClick()
        row(Audiobook).performClick()

        apply().performClick()

        assertShown(text(R.string.download_delete_title), "PDF, Audiobook")
    }

    @Test
    fun `on a metered network without the setting the sheet says the download will wait`() {
        show(situation(offered = ThreeFormats, network = Mobile), waitsForWifi = true)

        assertShown(text(R.string.download_sheet_metered))
    }

    @Test
    fun `a format being fetched is checked and says how far it is`() {
        val fetching = record(Audiobook, FormatState.Downloading(receivedBytes = 103 * Mb, totalBytes = 412 * Mb))
        show(situation(book = downloaded(fetching)))

        row(Audiobook).assertIsOn()
        assertShown(text(R.string.download_sheet_fetching_percent, 25))
    }

    @Test
    fun `unchecking a downloaded format asks before deleting, and says what is kept`() {
        show(situation(book = downloaded(complete(Text, 2 * Mb), complete(Audiobook, 982 * Mb))))
        row(Audiobook).assertIsOn().performClick()

        apply().performClick()

        // Nothing is deleted before the confirmation.
        assertThat(applied).isEmpty()
        assertShown(text(R.string.download_delete_title), text(R.string.download_delete_note))
        compose.onNodeWithTag(FormatsApplyTag).performScrollTo().performClick()
        assertThat(applied).containsExactly(SheetPlan(toDelete = setOf(Audiobook), deleteBytes = 982 * Mb))
    }

    @Test
    fun `cancelling the confirmation comes back to the formats, and cancelling those closes the sheet`() {
        show(situation(book = downloaded(complete(Text, 2 * Mb), complete(Audiobook, 982 * Mb))))
        row(Audiobook).performClick()
        apply().performClick()

        compose.onNodeWithTag(FormatsCancelTag).performScrollTo().performClick()

        assertThat(cancels).isEqualTo(0)
        row(Audiobook).assertIsOff()
        compose.onNodeWithTag(FormatsCancelTag).performScrollTo().performClick()
        assertThat(cancels).isEqualTo(1)
        assertThat(applied).isEmpty()
    }

    @Test
    fun `the button says what it will do`() {
        show(situation(book = downloaded(complete(Text, 2 * Mb))))
        val description = { apply().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single() }

        assertThat(description()).isEqualTo(text(R.string.download_sheet_download))
        row(Audiobook).performClick()
        assertThat(description()).isEqualTo(text(R.string.download_sheet_download_size, size(412 * Mb)))
        row(Text).performClick()
        assertThat(description()).isEqualTo(text(R.string.download_sheet_apply))
        row(Audiobook).performClick()
        assertThat(description()).isEqualTo(text(R.string.download_sheet_delete_size, size(2 * Mb)))
    }

    @Test
    fun `without the permission the sheet only removes, and says why`() {
        val book = downloaded(complete(Text, 2 * Mb), complete(Narration, 941 * Mb))
        show(situation(offered = ThreeFormats, book = book, canDownload = false))

        assertShown(text(R.string.download_delete_title), text(R.string.download_line_not_allowed))
        compose.onAllNodesWithTag(formatRowTag(Audiobook)).assertCountEquals(0)
        row(Text).assertIsOn().assertIsNotEnabled()
        assertShown(text(R.string.download_sheet_text_locked))
        compose.onAllNodesWithText(text(R.string.download_sheet_free, size(41_200 * Mb))).assertCountEquals(0)
        row(Narration).performClick()
        apply().performClick()
        compose.onNodeWithTag(FormatsApplyTag).performScrollTo().performClick()
        assertThat(applied).containsExactly(SheetPlan(toDelete = setOf(Narration), deleteBytes = 941 * Mb))
    }

    @Test
    fun `a format with an update says so under its name`() {
        show(situation(book = downloaded(updatable(Text), complete(Audiobook))))

        compose.onAllNodesWithText(text(R.string.download_sheet_update_available)).assertCountEquals(1)
        row(Text).assertTextContains(text(R.string.download_sheet_update_available))
    }
}
