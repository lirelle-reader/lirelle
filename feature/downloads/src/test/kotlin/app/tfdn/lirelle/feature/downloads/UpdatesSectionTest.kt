package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class UpdatesSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()
    private val actions = DownloadsActions(
        onCancel = { calls += "cancel $it" },
        onRetry = { calls += "retry $it" },
        onAskDelete = { calls += "delete $it" },
        updates = UpdateActions(
            onUpdate = { calls += "update $it" },
            onUpdateAll = { calls += "updateAll" },
            onRefresh = { calls += "refresh" },
        ),
    )

    private val text = listOf(DownloadFormat.Text)
    private val dune = DownloadedRow(BookId("1"), "Dune", "Frank Writer", null, text, 4 * Mb, percent = null)
    private val redMars = DownloadedRow(BookId("2"), "Red Mars", "Kim Author", null, text, 1_080 * Mb, percent = null)
    private val slowHorses =
        DownloadedRow(BookId("3"), "Slow Horses", "Mick Author", null, text, 412 * Mb, percent = null)
    private val listed = DownloadsUiState(
        storage = StorageUi(1_500 * Mb, bookCount = 3, freeBytes = 41 * Gb, totalBytes = 128 * Gb),
        updates = listOf(UpdateRow(redMars, 1_080 * Mb), UpdateRow(dune, 10 * Mb)),
        books = listOf(slowHorses),
        loaded = true,
    )

    private fun show(state: DownloadsUiState) {
        compose.setContent {
            LirelleTheme { DownloadsScreen(state, actions, onOpenBook = { calls += "open $it" }, onBrowseLibrary = {}) }
        }
    }

    private fun showProgress(status: ProgressStatus) {
        val row = ProgressRow(BookId("3"), "Slow Horses", null, text, status, update = true)
        compose.setContent {
            LirelleTheme { InProgressRow(row, card = false, onCancel = {}, onRetry = { calls += "retry" }) }
        }
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val updateWord get() = string(R.string.downloads_progress_update)

    @Test
    fun `the updates have their heading, how many books and how much, above the downloaded books`() {
        show(listed)

        val books = context.resources.getQuantityString(R.plurals.downloads_book_count, 2, 2)
        compose.onNodeWithText(string(R.string.downloads_updates_summary, books, context.fileSize(1_090 * Mb)))
            .assertIsDisplayed()
        val updates = compose.onNodeWithTag(UpdatesHeadingTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val downloaded = compose.onNodeWithText(string(R.string.downloads_downloaded)).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertThat(updates.bottom).isAtMost(downloaded.top)
    }

    @Test
    fun `update all asks for every update`() {
        show(listed)

        compose.onNodeWithTag(UpdateAllTag).performClick()

        assertThat(calls).containsExactly("updateAll")
    }

    @Test
    fun `a book to update is its usual row, what the update would fetch and a button that asks for it`() {
        show(listed)

        val row = hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("2"))))
        compose.onNode(hasText("Red Mars") and row, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Kim Author") and row, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(
            hasText(string(R.string.downloads_update_size, context.fileSize(1_080 * Mb))) and row,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.downloads_update_description, "Red Mars"))
            .assertIsDisplayed()
        compose.onNodeWithTag(updateTag(BookId("2"))).performClick()
        compose.onNodeWithTag(downloadedRowTag(BookId("1"))).performClick()

        assertThat(calls).containsExactly("update 2", "open 1").inOrder()
        compose.onNodeWithTag(updateTag(BookId("3"))).assertDoesNotExist()
    }

    @Test
    fun `an update being fetched says so, with what it received`() {
        showProgress(ProgressStatus.Active(324 * Mb, 1_080 * Mb))

        val received = context.fileSize(324 * Mb)
        compose.onNodeWithText(
            string(R.string.downloads_progress_of, updateWord, received, context.fileSize(1_080 * Mb)),
        ).assertIsDisplayed()
        compose.onNodeWithTag(RowProgressTag).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.downloads_cancel_update, "Slow Horses"))
            .assertIsDisplayed()
    }

    @Test
    fun `an update waiting for Wi-Fi says its size and what it waits for`() {
        showProgress(ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 0, 1_080 * Mb))

        val size = string(R.string.downloads_progress_received, updateWord, context.fileSize(1_080 * Mb))
        compose.onNodeWithText(
            string(R.string.downloads_progress_status, size, string(R.string.downloads_progress_wait_wifi)),
        ).assertIsDisplayed()
    }

    @Test
    fun `an update that failed says that it is the update, why, and can be retried`() {
        showProgress(ProgressStatus.Failed(DownloadFailure.Damaged))

        compose.onNodeWithText(
            string(R.string.downloads_update_failed, string(R.string.downloads_failure_damaged)),
        ).assertIsDisplayed()
        compose.onNodeWithTag(retryTag(BookId("3"))).performClick()

        assertThat(calls).containsExactly("retry")
    }

    @Test
    fun `a book the server no longer has says so on its row`() {
        show(listed.copy(updates = emptyList(), books = listOf(slowHorses.copy(note = RowNote.Gone), dune)))

        compose.onNode(
            hasText(string(R.string.downloads_gone)) and hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("3")))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag(UpdatesHeadingTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_downloaded)).assertDoesNotExist()
    }

    @Test
    fun `an update fetched while the book is open says when it will be applied`() {
        show(listed.copy(updates = emptyList(), books = listOf(slowHorses.copy(note = RowNote.UpdateReady))))

        compose.onNode(
            hasText(string(R.string.downloads_update_ready)) and
                hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("3")))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `pulling the list down asks for the check`() {
        show(listed)

        compose.onNodeWithTag(DownloadsListTag).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertThat(calls).containsExactly("refresh")
    }
}
