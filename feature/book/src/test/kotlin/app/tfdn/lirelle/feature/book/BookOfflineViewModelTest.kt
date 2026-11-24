package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities.Companion.Everything
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.download.FakeDownloadUpdates
import app.tfdn.lirelle.feature.book.download.FakeDownloads
import app.tfdn.lirelle.feature.book.download.FakeKeptPages
import app.tfdn.lirelle.feature.book.download.KeptBookCheck
import app.tfdn.lirelle.feature.book.download.KeptPage
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BookOfflineViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val detail = sampleBookDetail(id = "12")
    private val reading = BookReading(42, Instant.parse("2026-03-01T20:00:00Z"), totalSeconds = 7_200, sessionCount = 5)
    private val repository = FakeLibraryRepository().apply {
        bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
    }
    private val audio = FakeAudioRepository()
    private val kept = FakeKeptPages()
    private val check = KeptBookCheck(FakeDownloads(), FakeDownloadUpdates())

    private fun viewModel() =
        BookViewModel("12", repository, audio, MonotonicClock { 0 }, ReadingChanges(), kept, check, Everything)
            .also { it.onShown() }

    @Test
    fun `without a network the page of a downloaded book is built from what the device keeps`() =
        runTest(mainRule.dispatcher) {
            kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 11)

            val state = viewModel().state.value

            assertThat(state.book).isEqualTo(Loadable.Ready(detail))
            assertThat(state.offline).isTrue()
            assertThat(state.noNetwork).isTrue()
            assertThat(state.keptFormats).containsExactly(DownloadFormat.Text)
            // The device's position, and none of what only the server knows.
            assertThat(state.reading).isEqualTo(BookReading(11, lastReadAt = null, totalSeconds = 0, sessionCount = 0))
            assertThat(state.related).isEqualTo(RelatedBooks())
            // The player goes on from where it knows.
            assertThat(state.listening).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
            assertThat(repository.calls).containsExactly("book 12")
            assertThat(audio.calls).isEmpty()
        }

    @Test
    fun `without a network the listening is where the device knows it is`() = runTest(mainRule.dispatcher) {
        val listening = PlaybackState("aud_one", 14_000, 1.4, completed = false, null, version = "", null)
        kept.pages[BookId("12")] = KeptPage(detail, setOf(DownloadFormat.Audiobook), percent = 1, listening = listening)

        assertThat(viewModel().state.value.listening).isEqualTo(Loadable.Ready(listening))
        assertThat(audio.calls).isEmpty()
    }

    @Test
    fun `a downloaded book never started has no reading`() = runTest(mainRule.dispatcher) {
        kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = null)

        assertThat(viewModel().state.value.reading).isNull()
    }

    @Test
    fun `a downloaded book gone from the server still has its page, without the no-network chip`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Failure(ApiError.NotFound)
            kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Audiobook), percent = null)

            val state = viewModel().state.value

            assertThat(state.book).isEqualTo(Loadable.Ready(detail))
            assertThat(state.offline).isTrue()
            assertThat(state.noNetwork).isFalse()
        }

    @Test
    fun `a book that is not downloaded fails without a network`() = runTest(mainRule.dispatcher) {
        val state = viewModel().state.value

        assertThat(state.book).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
        assertThat(state.offline).isFalse()
    }

    @Test
    fun `another failure does not show the kept page`() = runTest(mainRule.dispatcher) {
        repository.bookResult = ApiResult.Failure(ApiError.ServerFailed)
        kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 11)

        assertThat(viewModel().state.value.book).isEqualTo(Loadable.Failed(ApiError.ServerFailed))
    }

    @Test
    fun `a refresh that fails without a network says nothing more on the offline page`() =
        runTest(mainRule.dispatcher) {
            kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 11)
            val viewModel = viewModel()

            viewModel.refresh()

            val state = viewModel.state.value
            assertThat(state.refreshing).isFalse()
            assertThat(state.refreshError).isNull()
            assertThat(state.offline).isTrue()
        }

    @Test
    fun `the network back, a refresh shows the page of the server again`() = runTest(mainRule.dispatcher) {
        kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 11)
        val viewModel = viewModel()
        repository.bookResult = ApiResult.Success(detail)
        repository.readingResult = ApiResult.Success(reading)

        viewModel.refresh()

        val state = viewModel.state.value
        assertThat(state.offline).isFalse()
        assertThat(state.noNetwork).isFalse()
        assertThat(state.keptFormats).isEmpty()
        assertThat(state.reading).isEqualTo(reading)
    }

    @Test
    fun `back from reading a downloaded book without a network, the page shows where the device is`() =
        runTest(mainRule.dispatcher) {
            kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 0)
            val viewModel = viewModel()
            assertThat(viewModel.state.value.reading?.progressPercent).isEqualTo(0)

            // Read to the middle in the reader: the places are kept on the device, the server cannot be reached.
            viewModel.onHidden()
            kept.pages[BookId("12")] = KeptPage(
                detail,
                complete = setOf(DownloadFormat.Text),
                percent = 50,
                ownAt = Instant.parse("2026-11-23T09:00:00Z"),
            )
            viewModel.onShown()

            val state = viewModel.state.value
            assertThat(state.offline).isTrue()
            assertThat(state.reading?.progressPercent).isEqualTo(50)
        }

    @Test
    fun `back from listening to a downloaded book without a network, the page shows where the device is`() =
        runTest(mainRule.dispatcher) {
            val before = PlaybackState("aud_one", 14_000, 1.4, completed = false, null, version = "", null)
            kept.pages[BookId("12")] =
                KeptPage(detail, setOf(DownloadFormat.Audiobook), percent = 1, listening = before)
            val viewModel = viewModel()

            // Listened further in the player: the position is kept on the device, the server cannot be reached.
            viewModel.onHidden()
            val after = before.copy(positionMs = 90_000)
            kept.pages[BookId("12")] = KeptPage(detail, setOf(DownloadFormat.Audiobook), percent = 5, listening = after)
            viewModel.onShown()

            val state = viewModel.state.value
            assertThat(state.offline).isTrue()
            assertThat(state.listening).isEqualTo(Loadable.Ready(after))
            assertThat(state.reading?.progressPercent).isEqualTo(5)
        }

    @Test
    fun `back on the page of a downloaded book once the network is back, the page is the server's again`() =
        runTest(mainRule.dispatcher) {
            kept.pages[BookId("12")] = KeptPage(detail, complete = setOf(DownloadFormat.Text), percent = 11)
            val viewModel = viewModel()

            viewModel.onHidden()
            repository.bookResult = ApiResult.Success(detail)
            repository.readingResult = ApiResult.Success(reading)
            viewModel.onShown()

            val state = viewModel.state.value
            assertThat(state.offline).isFalse()
            assertThat(state.noNetwork).isFalse()
            assertThat(state.keptFormats).isEmpty()
            assertThat(state.reading).isEqualTo(reading)
            assertThat(state.refreshError).isNull()
        }

    // --- a kept comic whose files the server cannot serve for now (Komga analyses its new file)

    private val comic = sampleComicDetail(id = "12")

    /** The server's page of the comic while Komga analyses its new file: a newer title, no file. */
    private val analysed = comic.copy(title = "A Newer Title", files = emptyList(), filesUnavailable = true)

    @Test
    fun `a kept comic whose files the server cannot serve for now is read from the kept copy, saying nothing`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)
            repository.readingResult = ApiResult.Success(reading)
            kept.pages[BookId("12")] = KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 11)

            val state = viewModel().state.value

            val shown = (state.book as Loadable.Ready).value
            // The server's page, with the files the device keeps: Read opens them, as without a network.
            assertThat(shown).isEqualTo(analysed.copy(files = comic.files, filesUnavailable = false))
            assertThat(Openings(shown, kept = null).read).isTrue()
            assertThat(state.offline).isFalse()
            assertThat(state.noNetwork).isFalse()
            assertThat(state.reading).isEqualTo(reading)
            // What is kept again still names the kept files.
            assertThat(kept.keeps).containsExactly(shown to reading)
        }

    @Test
    fun `a kept comic whose files the server cannot serve for now is read from the kept copy after a refresh`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(comic)
            kept.pages[BookId("12")] = KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 11)
            val viewModel = viewModel()
            repository.bookResult = ApiResult.Success(analysed)

            viewModel.refresh()

            val shown = (viewModel.state.value.book as Loadable.Ready).value
            assertThat(shown.title).isEqualTo("A Newer Title")
            assertThat(shown.pagedFile).isEqualTo(comic.pagedFile)
        }

    @Test
    fun `a comic whose kept copy is not whole on the device, while the server cannot serve it, has nothing to read`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)
            // A page and a record, but the pages are still on their way, or being deleted.
            kept.pages[BookId("12")] = KeptPage(comic, complete = emptySet(), percent = 11)

            val shown = (viewModel().state.value.book as Loadable.Ready).value

            assertThat(shown).isEqualTo(analysed)
            assertThat(Openings(shown, kept = null).read).isFalse()
        }

    @Test
    fun `a comic not kept whose files the server cannot serve for now has nothing to read`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)

            val shown = (viewModel().state.value.book as Loadable.Ready).value

            assertThat(shown).isEqualTo(analysed)
            assertThat(Openings(shown, kept = null).read).isFalse()
        }

    // --- what was read of the kept copy while the server cannot serve the book: its places wait to be sent

    private val readOnDevice = Instant.parse("2026-11-23T09:00:00Z")

    /** The server's reading of a book never started there. */
    private val neverStarted = BookReading(null, lastReadAt = null, totalSeconds = 0, sessionCount = 0)

    @Test
    fun `what was read of the kept copy while the server cannot serve the book shows on its page`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)
            repository.readingResult = ApiResult.Success(neverStarted)
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)

            val state = viewModel().state.value

            val shown = (state.book as Loadable.Ready).value
            assertThat(state.reading).isEqualTo(
                BookReading(35, readOnDevice, totalSeconds = 0, sessionCount = 0, positionAt = readOnDevice),
            )
            // The ring says how far, and Read goes on from there.
            assertThat(readStatus(Openings(shown, kept = null).read, finished = false, percent = 35))
                .isEqualTo(RingStatus.ResumeAt(35))
            // What is kept again is still what the server said.
            assertThat(kept.keeps).containsExactly(shown to neverStarted)
        }

    @Test
    fun `back from reading the kept copy, the page shows what was read, without asking the server`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)
            repository.readingResult = ApiResult.Success(neverStarted)
            kept.pages[BookId("12")] = KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = null)
            val viewModel = viewModel()
            assertThat(viewModel.state.value.reading).isEqualTo(neverStarted)
            val asked = repository.calls.toList()

            // Pages turned in the reader: their places wait to be sent, and say no change to the page.
            viewModel.onHidden()
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)
            viewModel.onShown()

            assertThat(viewModel.state.value.reading?.progressPercent).isEqualTo(35)
            assertThat(repository.calls).isEqualTo(asked)
        }

    @Test
    fun `back on a page the server serves whole, nothing is shown of the device's reading`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(comic)
            repository.readingResult = ApiResult.Success(neverStarted)
            kept.pages[BookId("12")] = KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = null)
            val viewModel = viewModel()

            viewModel.onHidden()
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)
            viewModel.onShown()

            assertThat(viewModel.state.value.reading).isEqualTo(neverStarted)
        }

    @Test
    fun `the device's reading of the kept copy shows when the server's cannot be had`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(analysed)
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)

            assertThat(viewModel().state.value.reading?.progressPercent).isEqualTo(35)
        }

    @Test
    fun `a reading newer on the server than the device's stays the server's, on the kept copy too`() =
        runTest(mainRule.dispatcher) {
            val later =
                BookReading(60, null, totalSeconds = 0, sessionCount = 0, positionAt = readOnDevice.plusSeconds(60))
            repository.bookResult = ApiResult.Success(analysed)
            repository.readingResult = ApiResult.Success(later)
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)

            assertThat(viewModel().state.value.reading).isEqualTo(later)
        }

    @Test
    fun `a page the server serves whole shows the server's reading, whatever the device read`() =
        runTest(mainRule.dispatcher) {
            repository.bookResult = ApiResult.Success(comic)
            repository.readingResult = ApiResult.Success(neverStarted)
            kept.pages[BookId("12")] =
                KeptPage(comic, complete = setOf(DownloadFormat.Text), percent = 35, ownAt = readOnDevice)

            assertThat(viewModel().state.value.reading).isEqualTo(neverStarted)
        }

    @Test
    fun `a page loaded with a network is kept again, with where the reading is`() = runTest(mainRule.dispatcher) {
        repository.bookResult = ApiResult.Success(detail)
        repository.readingResult = ApiResult.Success(reading)

        viewModel()

        assertThat(kept.keeps).containsExactly(detail to reading)
    }

    @Test
    fun `a page whose reading cannot be had is kept again without it`() = runTest(mainRule.dispatcher) {
        repository.bookResult = ApiResult.Success(detail)

        viewModel()

        assertThat(kept.keeps).containsExactly(detail to null)
    }
}
