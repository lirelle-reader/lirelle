package app.tfdn.lirelle.feature.library.series

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.library.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SeriesDownloadSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val confirmed = mutableListOf<Pair<Set<DownloadFormat>, Boolean>>()
    private var retries = 0
    private var cancels = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any) = context.resources.getQuantityString(id, count, *args)

    private fun size(bytes: Long) = context.fileSize(bytes)

    private fun show(sheet: SeriesSheetState) {
        compose.setContent {
            LirelleTheme {
                SeriesDownloadSheetContent(
                    sheet = sheet,
                    onCancel = { cancels++ },
                    onRetry = { retries++ },
                    onConfirm = { chosen, skip -> confirmed += chosen to skip },
                )
            }
        }
    }

    private fun row(format: DownloadFormat) = compose.onNodeWithTag(seriesFormatRowTag(format)).performScrollTo()

    private fun confirm() = compose.onNodeWithTag(SeriesSheetConfirmTag).performScrollTo()

    /** Each of [texts] is somewhere on the sheet. */
    private fun assertShown(vararg texts: String) = texts.forEach {
        assertWithMessage(it).that(compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `a row for each format with how many books and what they take, and a button that says what it will do`() {
        show(sampleSheet(chosen = setOf(Text, Audiobook)))

        row(Text).assertIsOn().assertIsEnabled()
        row(Audiobook).assertIsOn()
        row(Narration).assertIsOff()
        compose.onNodeWithTag(SeriesSheetSkipTag).performScrollTo().assertIsOn()
        assertThat(text(R.string.series_download_subtitle_kept, "Dawn", "7 books", 2))
            .isEqualTo("Dawn · 7 books, 2 already on the device")
        assertShown(
            text(R.string.series_download_button),
            "Dawn · 7 books, 2 already on the device",
            "4 of 4 books",
            "≈ ${size(9_600_000)}",
            "3 of 4 books",
            size(3_120 * Mb),
            "2 of 4 books · includes the text",
            size(2_040 * Mb),
            "3 finished books will not be downloaded",
            text(R.string.series_download_free, size(41_200 * Mb)),
        )
        compose.onAllNodesWithText(text(R.string.series_download_metered)).assertCountEquals(0)
        compose.onAllNodesWithText("Not enough space", substring = true).assertCountEquals(0)
        confirm().assertIsEnabled()
        compose.onNodeWithContentDescription("4 books · ${size(3_129_600_000)}").assertExists()

        confirm().performClick()
        assertThat(confirmed).containsExactly(setOf(Text, Audiobook) to true)
    }

    /** What the row of [format] is named. */
    private fun name(format: DownloadFormat, word: String) = compose.onNode(
        hasText(word) and hasAnyAncestor(hasTestTag(seriesFormatRowTag(format))),
        useUnmergedTree = true,
    )

    @Test
    fun `a series of comics of one format names its text row by it, the other rows as they are`() {
        val names = mapOf(
            TextName.Cbz to "CBZ", TextName.Cbr to "CBR", TextName.Cb7 to "CB7", TextName.Djvu to "DjVu",
            TextName.Pdf to "PDF", TextName.Text to "Text",
        )
        var sheet by mutableStateOf(sampleSheet())
        compose.setContent {
            LirelleTheme { SeriesDownloadSheetContent(sheet, onCancel = {}, onRetry = {}, onConfirm = { _, _ -> }) }
        }

        names.forEach { (textName, word) ->
            sheet = sampleSheet(offers = Loadable.Ready(pagedOffers(textName, textName)))

            row(Text)
            name(Text, word).assertExists()
            name(Audiobook, "Audiobook").assertExists()
            if (word != "Text") name(Text, "Text").assertDoesNotExist()
        }
    }

    @Test
    fun `a series whose books are of different formats keeps the word for them all`() {
        show(sampleSheet(offers = Loadable.Ready(pagedOffers(TextName.Cbz, TextName.Cbr, TextName.Text))))

        row(Text)
        name(Text, "Text").assertExists()
        name(Text, "CBZ").assertDoesNotExist()
        name(Text, "CBR").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the rows in French, a reflowable text as it was`() {
        show(sampleSheet(offers = Loadable.Ready(pagedOffers(TextName.Text, TextName.Text))))
        row(Text)
        name(Text, "Texte").assertExists()
        name(Audiobook, "Livre audio").assertExists()
    }

    @Test
    fun `the narration checks the text and locks it`() {
        show(sampleSheet(chosen = emptySet()))
        row(Text).assertIsOff()

        row(Narration).performClick()

        row(Narration).assertIsOn()
        row(Text).assertIsOn().assertIsNotEnabled()

        confirm().performClick()
        assertThat(confirmed).containsExactly(setOf(Text, Narration) to true)
    }

    @Test
    fun `finished books come back once the box is unchecked`() {
        show(sampleSheet(chosen = setOf(Text)))

        compose.onNodeWithTag(SeriesSheetSkipTag).performScrollTo().performClick()

        compose.onNodeWithTag(SeriesSheetSkipTag).assertIsOff()
        assertShown("7 of 7 books", "3 finished books")
        compose.onAllNodesWithText("will not be downloaded", substring = true).assertCountEquals(0)
        confirm().performClick()
        assertThat(confirmed).containsExactly(setOf(Text) to false)
    }

    @Test
    fun `nothing to download disables the button`() {
        show(sampleSheet(chosen = emptySet()))

        confirm().assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.series_download_nothing)).assertExists()

        row(Text).performClick()
        confirm().assertIsEnabled()
    }

    @Test
    fun `a sum larger than the space left says how much is missing, the button staying`() {
        show(sampleSheet(chosen = setOf(Text, Audiobook), freeBytes = 1_000 * Mb, waitsForWifi = true))

        assertShown(
            text(R.string.series_download_missing, size(2_129_600_000)),
            text(R.string.series_download_metered),
        )
        confirm().assertIsEnabled()
    }

    @Test
    fun `no finished book, no box to skip them`() {
        show(sampleSheet(offers = Loadable.Ready(MockOffers.filterNot { it.finished })))

        compose.onAllNodesWithTag(SeriesSheetSkipTag).assertCountEquals(0)
    }

    @Test
    fun `while the pages are read the rows wait, and the sheet can only be left`() {
        show(sampleSheet(offers = Loadable.Loading))

        compose.onNodeWithTag(SeriesSheetLoadingTag).assertExists()
        compose.onAllNodesWithTag(SeriesSheetConfirmTag).assertCountEquals(0)
        compose.onNodeWithTag(SeriesSheetCancelTag).performScrollTo().performClick()
        assertThat(cancels).isEqualTo(1)
    }

    @Test
    fun `a failure shows the usual error and its retry`() {
        show(sampleSheet(offers = Loadable.Failed(ApiError.ServerFailed)))

        compose.onAllNodesWithTag(SeriesSheetConfirmTag).assertCountEquals(0)
        compose.onNodeWithText(text(UiR.string.state_retry)).performScrollTo().performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `the counts agree with one book or several`() {
        assertThat(plural(R.plurals.series_download_skipped, 3, 3)).isEqualTo("3 finished books will not be downloaded")
        assertThat(plural(R.plurals.series_download_confirm_size, 1, 1, "3 MB")).isEqualTo("1 book · 3 MB")
        assertThat(plural(R.plurals.series_download_whole, 1, 1)).isEqualTo("1 book")
    }
}
