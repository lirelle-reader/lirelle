package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.downloads.R as DownloadsR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class InProgressRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val narration = context.getString(DownloadsR.string.downloads_format_narration)

    private fun show(status: ProgressStatus, title: String? = "Slow Horses") =
        show(ProgressRow(BookId("3"), title, null, listOf(DownloadFormat.Narration), status))

    private fun show(row: ProgressRow) {
        compose.setContent {
            LirelleTheme { InProgressRow(row, card = false, onCancel = {}, onRetry = {}) }
        }
    }

    private fun status(detail: String, wait: Int) =
        context.getString(R.string.downloads_progress_status, detail, context.getString(wait))

    private fun received(received: Long, total: Long) = context.getString(
        R.string.downloads_progress_of,
        narration,
        context.fileSize(received),
        context.fileSize(total),
    )

    @Test
    fun `a download waiting for Wi-Fi keeps what it received, with a bar that is not the running one`() {
        show(ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 11 * Mb, 98 * Mb))

        compose.onNodeWithText(status(received(11 * Mb, 98 * Mb), R.string.downloads_progress_wait_wifi))
            .assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertIsDisplayed()
        compose.onNodeWithTag(RowProgressTag).assertDoesNotExist()
    }

    @Test
    fun `a download whose page the device does not have is a book, not a row without a title`() {
        show(ProgressStatus.Queued(98 * Mb), title = null)

        val book = context.getString(R.string.downloads_untitled)
        compose.onNodeWithText(book).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.downloads_cancel, book)).assertIsDisplayed()
    }

    @Test
    fun `a download waiting for the network says so`() {
        show(ProgressStatus.Waiting(WaitReason.NoNetwork, 11 * Mb, 98 * Mb))

        compose.onNodeWithText(status(received(11 * Mb, 98 * Mb), R.string.downloads_progress_wait_network))
            .assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertIsDisplayed()
    }

    @Test
    fun `a waiting download that received nothing says its size, without a bar`() {
        show(ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 0, 98 * Mb))

        val size = context.getString(R.string.downloads_progress_received, narration, context.fileSize(98 * Mb))
        compose.onNodeWithText(status(size, R.string.downloads_progress_wait_wifi)).assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertDoesNotExist()
    }

    @Test
    fun `a waiting download of an unknown size says what it received, or only that it waits`() {
        show(ProgressStatus.Waiting(WaitReason.NoNetwork, 0, null))

        compose.onNodeWithText(status(narration, R.string.downloads_progress_wait_network)).assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertDoesNotExist()
    }

    @Test
    fun `a download the server refused for the account's rights says downloads are not allowed`() {
        show(ProgressStatus.Failed(DownloadFailure.NotAllowed))

        compose.onNodeWithText(context.getString(R.string.downloads_failure_not_allowed)).assertIsDisplayed()
        compose.onNodeWithTag(retryTag(BookId("3"))).assertIsDisplayed()
    }

    private fun album(status: ProgressStatus, kept: Int, name: TextName = TextName.Cbz): ProgressRow {
        val pages = PageCount(kept, total = 48)
        return ProgressRow(
            BookId("3"),
            "Blacksad",
            null,
            listOf(DownloadFormat.Text),
            status,
            textName = name,
            pages = pages,
        )
    }

    private fun bar(tag: String, fraction: Float) =
        compose.onNodeWithTag(tag).assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(fraction, 0f..1f)))

    @Test
    fun `an album being fetched says its pages, its bar following them and not the bytes`() {
        show(album(ProgressStatus.Active(8 * Mb, 9 * Mb), kept = 12))

        compose.onNodeWithText("CBZ · 12 pages of 48").assertIsDisplayed()
        bar(RowProgressTag, 0.25f)
        compose.onNodeWithTag(WaitingProgressTag).assertDoesNotExist()
    }

    @Test
    fun `an album whose size is not known still has a bar that follows its pages`() {
        show(album(ProgressStatus.Active(8 * Mb, null), kept = 36, name = TextName.Djvu))

        compose.onNodeWithText("DjVu · 36 pages of 48").assertIsDisplayed()
        bar(RowProgressTag, 0.75f)
    }

    @Test
    fun `an album waiting for a network says the pages it reached, with the bar that is not the running one`() {
        show(album(ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 8 * Mb, 9 * Mb), kept = 12))

        compose.onNodeWithText("CBZ · 12 pages of 48 · waiting for an unmetered network").assertIsDisplayed()
        bar(WaitingProgressTag, 0.25f)
        compose.onNodeWithTag(RowProgressTag).assertDoesNotExist()
    }

    @Test
    fun `an album waiting with no page yet says so, without a bar`() {
        show(album(ProgressStatus.Waiting(WaitReason.NoNetwork, 0, 9 * Mb), kept = 0))

        compose.onNodeWithText("CBZ · 0 pages of 48 · waiting for the network").assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the pages of an album, in French`() {
        show(album(ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 8 * Mb, 9 * Mb), kept = 12))

        compose.onNodeWithText("CBZ · 12 pages sur 48 · en attente d'un réseau non limité").assertIsDisplayed()
    }

    @Test
    fun `the update of an album says its pages as an update`() {
        show(album(ProgressStatus.Active(8 * Mb, 9 * Mb), kept = 12).copy(update = true))

        compose.onNodeWithText("Update · 12 pages of 48").assertIsDisplayed()
        bar(RowProgressTag, 0.25f)
    }

    @Test
    fun `a queued album says its size as any queued download`() {
        show(album(ProgressStatus.Queued(9 * Mb), kept = 12))

        compose.onNodeWithText("CBZ · ${context.fileSize(9 * Mb)} · queued").assertIsDisplayed()
    }

    @Test
    fun `a text read by pages is named by its file, alone and beside an audiobook`() {
        val both = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        val active = ProgressStatus.Active(11 * Mb, 98 * Mb)
        show(ProgressRow(BookId("3"), "A", null, both, active, textName = TextName.Pdf))

        val size = "${context.fileSize(11 * Mb)} of ${context.fileSize(98 * Mb)}"
        compose.onNodeWithText("PDF and audiobook · $size").assertIsDisplayed()
        bar(RowProgressTag, 11f / 98f)
    }

    @Test
    fun `the download being fetched has the running bar`() {
        show(ProgressStatus.Active(11 * Mb, 98 * Mb))

        compose.onNodeWithText(received(11 * Mb, 98 * Mb)).assertIsDisplayed()
        compose.onNodeWithTag(RowProgressTag).assertIsDisplayed()
        compose.onNodeWithTag(WaitingProgressTag).assertDoesNotExist()
    }
}
