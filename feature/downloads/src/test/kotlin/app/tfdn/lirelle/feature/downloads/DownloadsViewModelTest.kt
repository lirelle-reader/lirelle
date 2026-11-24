package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DownloadsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val removals = FakeRemovals()
    private val settings = FakeDownloadSettings()
    private val dune = sampleBookDetail(id = "1").copy(
        title = "Dune",
        authors = listOf(AuthorRef(AuthorId("1"), "Frank Writer"), AuthorRef(AuthorId("2"), "Ann Other")),
        coverShape = CoverShape.Square,
    )
    private val redMars = sampleBookDetail(id = "2").copy(title = "Red Mars")
    private val kept = FakeKeptBookData(mutableMapOf(BookId("1") to dune, BookId("2") to redMars))
    private val percents = mutableMapOf<BookId, Int>()
    private val sources = DownloadsSources(kept, { percents[it.bookId] }, { Storage })

    private val severalFormats = downloaded(
        "2",
        DownloadFormat.Text to 3 * Mb,
        DownloadFormat.Audiobook to 982 * Mb,
        DownloadFormat.Narration to 941 * Mb,
    )

    /** The view model with its state collected, as a screen does. */
    private fun TestScope.viewModel(): DownloadsViewModel =
        viewModel(sources)

    private fun TestScope.viewModel(sources: DownloadsSources): DownloadsViewModel =
        DownloadsViewModel(downloads, removals, settings, sources, FakeDownloadUpdates())
            .also { it.state.launchIn(backgroundScope + mainRule.dispatcher) }

    @Test
    fun `nothing is shown until the device said what it keeps`() = runTest {
        val viewModel = DownloadsViewModel(downloads, removals, settings, sources, FakeDownloadUpdates())

        assertThat(viewModel.state.value.loaded).isFalse()
        assertThat(viewModel.state.value.isEmpty).isFalse()
    }

    @Test
    fun `a row is made of the page kept, its cover and the progress of the device`() = runTest {
        val cover = File("cover-of-1")
        kept.covers[BookId("1")] = cover
        percents[BookId("1")] = 42
        downloads.kept.value = listOf(downloaded("1"))

        val row = viewModel().state.value.books.single()

        assertThat(row.title).isEqualTo("Dune")
        assertThat(row.authors).isEqualTo("Frank Writer, Ann Other")
        assertThat(row.cover).isEqualTo(coverAddress(cover))
        assertThat(row.coverShape).isEqualTo(CoverShape.Square)
        assertThat(row.percent).isEqualTo(42)
        assertThat(viewModel().state.value.storage.totalBytes).isEqualTo(128 * Gb)
    }

    @Test
    fun `the progress is read again each time the screen is shown`() = runTest {
        downloads.kept.value = listOf(downloaded("1"))
        val viewModel = viewModel()
        assertThat(viewModel.state.value.books.single().percent).isNull()

        percents[BookId("1")] = 7
        viewModel.onShown()

        assertThat(viewModel.state.value.books.single().percent).isEqualTo(7)
    }

    @Test
    fun `the queue's ticks do not read the pages again`() = runTest {
        var reads = 0
        val counting = DownloadsSources(kept, { reads++ }, { Storage })
        downloads.kept.value = listOf(downloaded("1"))
        val viewModel = viewModel(counting)

        downloads.queued.value = IdleQueue.copy(active = entry("1", receivedBytes = 1))
        downloads.queued.value = IdleQueue.copy(active = entry("1", receivedBytes = 2))

        assertThat(reads).isEqualTo(1)
        assertThat(viewModel.state.value.inProgress.single().status).isEqualTo(ProgressStatus.Active(2, 412 * Mb))
    }

    @Test
    fun `cancel takes the book out of the queue`() = runTest {
        viewModel().cancel(BookId("3"))

        assertThat(downloads.calls).containsExactly("cancel 3 null")
    }

    @Test
    fun `retry queues the failed book again`() = runTest {
        viewModel().retry(BookId("3"))

        assertThat(downloads.calls).containsExactly("retry 3")
    }

    @Test
    fun `download now lets the queue use any network and leaves the setting alone`() = runTest {
        viewModel().downloadNow()

        assertThat(downloads.calls).containsExactly("allowAnyNetworkNow")
        assertThat(settings.current.value.allowMetered).isFalse()
    }

    @Test
    fun `always allowing the mobile network flips the setting and says so`() = runTest {
        val viewModel = viewModel()

        viewModel.alwaysAllowMobile()

        assertThat(settings.current.value.allowMetered).isTrue()
        assertThat(viewModel.state.value.snackbar?.kind).isEqualTo(DownloadsSnackbar.Kind.MobileAllowed)
    }

    @Test
    fun `undoing it sets the setting back`() = runTest {
        val viewModel = viewModel()
        viewModel.alwaysAllowMobile()

        viewModel.snackbarDone(checkNotNull(viewModel.state.value.snackbar), undo = true)

        assertThat(settings.current.value.allowMetered).isFalse()
        assertThat(viewModel.state.value.snackbar).isNull()
    }

    @Test
    fun `a message that goes on its own changes nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.alwaysAllowMobile()

        viewModel.snackbarDone(checkNotNull(viewModel.state.value.snackbar), undo = false)

        assertThat(settings.current.value.allowMetered).isTrue()
        assertThat(viewModel.state.value.snackbar).isNull()
    }

    @Test
    fun `a book with one format is deleted at once, with a message`() = runTest {
        downloads.kept.value = listOf(downloaded("1", DownloadFormat.Audiobook to 900 * Mb))
        val viewModel = viewModel()

        viewModel.askDelete(BookId("1"))

        assertThat(removals.calls).containsExactly("delete 1 [Audiobook]")
        assertThat(viewModel.state.value.deleteSheet).isNull()
        val snackbar = checkNotNull(viewModel.state.value.snackbar)
        assertThat(snackbar.kind).isEqualTo(DownloadsSnackbar.Kind.Deleted)
        assertThat(snackbar.bookId).isEqualTo(BookId("1"))
    }

    @Test
    fun `undoing a deletion brings the book back`() = runTest {
        downloads.kept.value = listOf(downloaded("1"))
        val viewModel = viewModel()
        viewModel.askDelete(BookId("1"))

        viewModel.snackbarDone(checkNotNull(viewModel.state.value.snackbar), undo = true)

        assertThat(removals.calls).containsExactly("delete 1 [Text]", "undoDelete 1").inOrder()
    }

    @Test
    fun `two deletions in a row are two messages`() = runTest {
        downloads.kept.value = listOf(downloaded("1"), downloaded("2"))
        val viewModel = viewModel()

        viewModel.askDelete(BookId("1"))
        val first = viewModel.state.value.snackbar
        viewModel.askDelete(BookId("2"))

        assertThat(viewModel.state.value.snackbar).isNotEqualTo(first)
        // The first message, replaced, ends after the second was asked: the second stays.
        viewModel.snackbarDone(checkNotNull(first), undo = false)
        assertThat(viewModel.state.value.snackbar?.bookId).isEqualTo(BookId("2"))
    }

    @Test
    fun `a book with several formats asks which ones, with their sizes`() = runTest {
        downloads.kept.value = listOf(severalFormats)
        val viewModel = viewModel()

        viewModel.askDelete(BookId("2"))

        assertThat(removals.calls).isEmpty()
        assertThat(viewModel.state.value.deleteSheet).isEqualTo(
            DeleteSheetUi(
                bookId = BookId("2"),
                title = "Red Mars",
                bytes = 1_926 * Mb,
                options = listOf(
                    DeleteOption(DownloadFormat.Text, 3 * Mb),
                    DeleteOption(DownloadFormat.Audiobook, 982 * Mb),
                    DeleteOption(DownloadFormat.Narration, 941 * Mb),
                ),
            ),
        )
    }

    @Test
    fun `the formats chosen are deleted, with a message`() = runTest {
        downloads.kept.value = listOf(severalFormats)
        val viewModel = viewModel()
        viewModel.askDelete(BookId("2"))

        viewModel.confirmDelete(setOf(DownloadFormat.Narration, DownloadFormat.Text))

        assertThat(removals.calls).containsExactly("delete 2 [Text, Narration]")
        assertThat(viewModel.state.value.deleteSheet).isNull()
        assertThat(viewModel.state.value.snackbar?.kind).isEqualTo(DownloadsSnackbar.Kind.Deleted)
    }

    @Test
    fun `closing the sheet deletes nothing`() = runTest {
        downloads.kept.value = listOf(severalFormats)
        val viewModel = viewModel()
        viewModel.askDelete(BookId("2"))

        viewModel.dismissDelete()

        assertThat(removals.calls).isEmpty()
        assertThat(viewModel.state.value.deleteSheet).isNull()
    }

    @Test
    fun `a book in use is not deleted, and the screen says why`() = runTest {
        removals.result = DeleteResult.InUse
        downloads.kept.value = listOf(downloaded("1"))
        val viewModel = viewModel()

        viewModel.askDelete(BookId("1"))

        assertThat(viewModel.state.value.snackbar?.kind).isEqualTo(DownloadsSnackbar.Kind.InUse)
    }

    @Test
    fun `a book that is gone meanwhile asks nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.askDelete(BookId("9"))

        assertThat(removals.calls).isEmpty()
        assertThat(viewModel.state.value.deleteSheet).isNull()
        assertThat(viewModel.state.value.snackbar).isNull()
    }
}
