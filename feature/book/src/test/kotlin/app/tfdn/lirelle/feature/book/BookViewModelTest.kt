package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities.Companion.Everything
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.download.FakeDownloadUpdates
import app.tfdn.lirelle.feature.book.download.FakeDownloads
import app.tfdn.lirelle.feature.book.download.FakeKeptPages
import app.tfdn.lirelle.feature.book.download.KeptBookCheck
import app.tfdn.lirelle.feature.book.download.complete
import app.tfdn.lirelle.feature.book.download.downloaded
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BookViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private var now = 0L
    private val clock = MonotonicClock { now }
    private val changes = ReadingChanges()

    private val reading = BookReading(
        progressPercent = 42,
        lastReadAt = Instant.parse("2026-03-01T20:00:00Z"),
        totalSeconds = 7_200,
        sessionCount = 5,
    )
    private val related = RelatedBooks(
        inSeries = listOf(RelatedBook(BookId("13"), "Next", listOf("Ursula Writer"), null, CoverShape.Portrait)),
    )
    private val fake = FakeLibraryRepository().apply {
        bookResult = ApiResult.Success(sampleBookDetail(id = "12"))
        readingResult = ApiResult.Success(reading)
        this.related = this@BookViewModelTest.related
    }
    private val listening = PlaybackState(
        assetId = "aud_1",
        positionMs = 900_000,
        percentage = 12.5,
        completed = false,
        capturedAt = Instant.parse("2026-03-02T08:00:00Z"),
        version = "v3",
        contentVersion = "rev",
    )
    private val audio = FakeAudioRepository().apply { playbackStateResult = ApiResult.Success(listening) }

    /** The page as it is made on screen: in front at once. */
    private fun shown(repository: LibraryRepository) = BookViewModel(
        "12", repository, audio, clock, changes, FakeKeptPages(), KeptBookCheck(downloads, updates), Everything,
    ).also { it.onShown() }

    private val downloads = FakeDownloads()
    private val updates = FakeDownloadUpdates()

    @Test
    fun `the page of a kept book checks it once when it loads, and not at each quiet reload`() =
        runTest(mainRule.dispatcher) {
            downloads.kept.value = downloaded(complete(DownloadFormat.Text))
            shown(fake)
            assertThat(updates.calls).containsExactly("checkBook 12")

            changes.changed()

            assertThat(fake.calls.count { it == "book 12" }).isEqualTo(2)
            assertThat(updates.calls).containsExactly("checkBook 12")
        }

    @Test
    fun `a reload the user asked for checks the kept book again`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(complete(DownloadFormat.Text))
        val viewModel = shown(fake)

        viewModel.refresh()

        assertThat(updates.calls).containsExactly("checkBook 12", "checkBook 12")
    }

    @Test
    fun `a book that is not kept is not checked`() = runTest(mainRule.dispatcher) {
        shown(fake).refresh()

        assertThat(updates.calls).isEmpty()
    }

    @Test
    fun `a kept book the server did not give is not checked`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(complete(DownloadFormat.Text))
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        shown(fake)

        assertThat(updates.calls).isEmpty()
    }

    @Test
    fun `a change while the page is not in front reloads nothing, then once when it is shown again`() =
        runTest(mainRule.dispatcher) {
            val viewModel = shown(fake)
            viewModel.onHidden()
            val before = fake.calls.count { it == "book 12" }

            repeat(times = 3) { changes.changed() }
            assertThat(fake.calls.count { it == "book 12" }).isEqualTo(before)

            viewModel.onShown()
            assertThat(fake.calls.count { it == "book 12" }).isEqualTo(before + 1)
        }

    @Test
    fun `a book with audio loads where its listening is`() = runTest(mainRule.dispatcher) {
        val state = shown(fake).state.value

        assertThat(state.listening).isEqualTo(Loadable.Ready(listening))
        assertThat(audio.calls).containsExactly("playbackState 12")
    }

    @Test
    fun `a book without an audio file asks nothing about listening`() = runTest(mainRule.dispatcher) {
        listOf(MediaProfile.TextOnly, MediaProfile.ReadAlong).forEach { media ->
            val book = sampleBookDetail(id = "12", media = media)
            fake.bookResult = ApiResult.Success(book.copy(files = book.files.filter { it.kind != BookFileKind.Audio }))

            val state = shown(fake).state.value

            assertThat(state.listening).isEqualTo(Loadable.Loading)
            assertThat(audio.calls).isEmpty()
        }
    }

    @Test
    fun `a failed listening call says so and keeps the rest`() = runTest(mainRule.dispatcher) {
        audio.playbackStateResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val state = shown(fake).state.value

        assertThat(state.listening).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
        assertThat(state.reading).isEqualTo(reading)
    }

    @Test
    fun `a book never listened to is known as such, apart from one not known yet`() = runTest(mainRule.dispatcher) {
        audio.playbackStateResult = ApiResult.Success(null)
        val gate = CompletableDeferred<Unit>()
        audio.gate = gate

        val viewModel = shown(fake)
        assertThat(viewModel.state.value.listening).isEqualTo(Loadable.Loading)

        gate.complete(Unit)
        assertThat(viewModel.state.value.listening).isEqualTo(Loadable.Ready(null))
    }

    @Test
    fun `a failed reload of the listening keeps what was known`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        audio.playbackStateResult = ApiResult.Failure(ApiError.ServerUnreachable)

        changes.changed()

        assertThat(viewModel.state.value.listening).isEqualTo(Loadable.Ready(listening))
    }

    @Test
    fun `the listening is asked again when this device changed it`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val further = listening.copy(percentage = 40.0, version = "v4")
        audio.playbackStateResult = ApiResult.Success(further)

        changes.changed()

        assertThat(viewModel.state.value.listening).isEqualTo(Loadable.Ready(further))
    }

    @Test
    fun `loads the book, then its reading and related books`() = runTest(mainRule.dispatcher) {
        val state = shown(fake).state.value

        assertThat((state.book as Loadable.Ready).value.id).isEqualTo(BookId("12"))
        assertThat(state.reading).isEqualTo(reading)
        assertThat(state.related).isEqualTo(related)
        assertThat(fake.calls.first()).isEqualTo("book 12")
    }

    @Test
    fun `the book is shown before the secondary calls answer`() = runTest(mainRule.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val slow = object : LibraryRepository by fake {
            override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> {
                hold.await()
                return fake.bookReading(bookId)
            }

            override suspend fun relatedBooks(bookId: BookId): RelatedBooks {
                hold.await()
                return fake.relatedBooks(bookId)
            }
        }
        val viewModel = shown(slow)

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.reading).isNull()
        assertThat(viewModel.state.value.related).isEqualTo(RelatedBooks())

        hold.complete(Unit)

        assertThat(viewModel.state.value.reading).isEqualTo(reading)
        assertThat(viewModel.state.value.related).isEqualTo(related)
    }

    @Test
    fun `a failed reading call leaves the reading out and keeps the rest`() = runTest(mainRule.dispatcher) {
        fake.readingResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val state = shown(fake).state.value

        assertThat(state.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(state.reading).isNull()
        assertThat(state.related).isEqualTo(related)
    }

    @Test
    fun `a book that is gone is a failure, and nothing else is asked`() = runTest(mainRule.dispatcher) {
        fake.bookResult = ApiResult.Failure(ApiError.NotFound)

        val viewModel = shown(fake)

        assertThat(viewModel.state.value.book).isEqualTo(Loadable.Failed(ApiError.NotFound))
        assertThat(fake.calls).containsExactly("book 12")
    }

    @Test
    fun `a merged book loads the reading of the book that replaced it`() = runTest(mainRule.dispatcher) {
        // The repository follows the merge: asked for 12, it answers with book 34.
        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "34"))

        shown(fake)

        assertThat(fake.calls).containsAtLeast("bookReading 34", "relatedBooks 34")
    }

    @Test
    fun `retrying after a failure loads everything`() = runTest(mainRule.dispatcher) {
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = shown(fake)

        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12"))
        viewModel.load()

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.reading).isEqualTo(reading)
    }

    @Test
    fun `refresh keeps the book on screen and replaces it`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12").copy(title = "Renamed"))

        viewModel.refresh()
        assertThat(viewModel.state.value.refreshing).isTrue()
        assertThat((viewModel.state.value.book as Loadable.Ready).value.title).isEqualTo("A Complete Book")

        gate.complete(Unit)
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat((viewModel.state.value.book as Loadable.Ready).value.title).isEqualTo("Renamed")
    }

    @Test
    fun `a failed refresh keeps the book and reports the error`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        viewModel.refresh()

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat(viewModel.state.value.refreshError).isEqualTo(ApiError.ServerUnreachable)
    }

    @Test
    fun `a refresh that works takes the error of the one before away`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        viewModel.refresh()

        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12"))
        viewModel.refresh()

        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `refreshing a page that failed loads it from nothing`() = runTest(mainRule.dispatcher) {
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = shown(fake)

        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12"))
        viewModel.refresh()

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `coming back reloads only what is older than two minutes`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val callsAfterLoad = fake.calls.size

        now = 60_000
        viewModel.onShown()
        assertThat(fake.calls).hasSize(callsAfterLoad)

        now = 121_000
        viewModel.onShown()
        assertThat(fake.calls.drop(callsAfterLoad)).contains("book 12")
        assertThat(viewModel.state.value.refreshing).isFalse()
    }

    @Test
    fun `a silent reload that fails shows nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        now = 121_000

        viewModel.onShown()

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.refreshError).isNull()
    }

    private fun bookCalls() = fake.calls.count { it == "book 12" }

    @Test
    fun `a reading that changed on the server reloads the page quietly, however fresh`() =
        runTest(mainRule.dispatcher) {
            val viewModel = shown(fake)
            val newer = reading.copy(progressPercent = 58)
            fake.readingResult = ApiResult.Success(newer)
            val gate = CompletableDeferred<Unit>()
            fake.gate = gate

            changes.changed()

            assertThat(bookCalls()).isEqualTo(2)
            assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
            assertThat(viewModel.state.value.refreshing).isFalse()
            assertThat(viewModel.state.value.reading).isEqualTo(reading)

            gate.complete(Unit)

            assertThat(viewModel.state.value.reading).isEqualTo(newer)
            assertThat(viewModel.state.value.refreshing).isFalse()
            assertThat(viewModel.state.value.refreshError).isNull()
        }

    @Test
    fun `without a change, a fresh page asks nothing more`() = runTest(mainRule.dispatcher) {
        changes.changed()
        val viewModel = shown(fake)

        now = 60_000
        viewModel.onShown()

        assertThat(bookCalls()).isEqualTo(1)
    }

    @Test
    fun `a change during the first load is followed once the load ends`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = shown(fake)

        changes.changed()
        assertThat(bookCalls()).isEqualTo(1)
        assertThat(viewModel.state.value.book).isEqualTo(Loadable.Loading)
        val newer = reading.copy(progressPercent = 58)
        fake.readingResult = ApiResult.Success(newer)

        gate.complete(Unit)

        assertThat(bookCalls()).isEqualTo(2)
        assertThat(viewModel.state.value.reading).isEqualTo(newer)
    }

    @Test
    fun `a change during a pull to refresh is followed once the refresh ends`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        viewModel.refresh()

        changes.changed()
        assertThat(bookCalls()).isEqualTo(2)
        assertThat(viewModel.state.value.refreshing).isTrue()

        gate.complete(Unit)

        assertThat(bookCalls()).isEqualTo(3)
        assertThat(viewModel.state.value.refreshing).isFalse()
    }

    @Test
    fun `a quiet reload after a change that fails shows nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        changes.changed()

        assertThat(bookCalls()).isEqualTo(2)
        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `a page that failed to load is not reloaded behind the user's back`() = runTest(mainRule.dispatcher) {
        fake.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = shown(fake)
        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12"))

        changes.changed()

        assertThat(bookCalls()).isEqualTo(1)
        assertThat(viewModel.state.value.book).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
    }

    @Test
    fun `being shown twice in a row reloads once`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        now = 121_000

        viewModel.onShown()
        viewModel.onShown()
        gate.complete(Unit)

        assertThat(bookCalls()).isEqualTo(2)
    }

    @Test
    fun `being shown while the first load runs asks nothing more`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = shown(fake)

        now = 500_000
        viewModel.onShown()
        gate.complete(Unit)

        assertThat(bookCalls()).isEqualTo(1)
        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
    }

    @Test
    fun `a pull during a silent reload takes its place`() = runTest(mainRule.dispatcher) {
        val answers = mutableListOf<CompletableDeferred<ApiResult<BookDetail>>>()
        val slow = object : LibraryRepository by fake {
            override suspend fun book(bookId: BookId): ApiResult<BookDetail> =
                CompletableDeferred<ApiResult<BookDetail>>().also { answers += it }.await()
        }
        val held = shown(slow)
        answers.removeAt(0).complete(ApiResult.Success(sampleBookDetail(id = "12")))
        now = 121_000
        held.onShown()

        held.refresh()
        val (silent, pulled) = answers
        silent.complete(ApiResult.Success(sampleBookDetail(id = "12").copy(title = "Silent")))

        // The answer of the silent reload is not shown, and does not end the refresh the user asked for.
        assertThat(held.state.value.refreshing).isTrue()
        assertThat((held.state.value.book as Loadable.Ready).value.title).isEqualTo("A Complete Book")

        pulled.complete(ApiResult.Success(sampleBookDetail(id = "12").copy(title = "Pulled")))
        assertThat(held.state.value.refreshing).isFalse()
        assertThat((held.state.value.book as Loadable.Ready).value.title).isEqualTo("Pulled")
    }

    @Test
    fun `a pull during a silent reload, with the gate, ends refreshed`() = runTest(mainRule.dispatcher) {
        val viewModel = shown(fake)
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        fake.bookResult = ApiResult.Success(sampleBookDetail(id = "12").copy(title = "Renamed"))
        now = 121_000

        viewModel.onShown()
        viewModel.refresh()
        viewModel.onShown()
        assertThat(viewModel.state.value.refreshing).isTrue()
        gate.complete(Unit)

        assertThat(bookCalls()).isEqualTo(3)
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat((viewModel.state.value.book as Loadable.Ready).value.title).isEqualTo("Renamed")
        // The reading and the related books are asked once for the answer that is shown.
        assertThat(fake.calls.count { it == "bookReading 12" }).isEqualTo(2)
    }

    @Test
    fun `a retry during a load starts over, and one answer is shown`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = shown(fake)

        viewModel.load()
        gate.complete(Unit)

        assertThat(viewModel.state.value.book).isInstanceOf(Loadable.Ready::class.java)
        assertThat(fake.calls.count { it == "bookReading 12" }).isEqualTo(1)
    }

    private fun related(id: String) =
        RelatedBook(BookId(id), "Book $id", listOf("Ursula Writer"), null, CoverShape.Portrait)

    private fun relatedOf(books: RelatedBooks): RelatedBooks {
        fake.related = books
        return shown(fake).state.value.related
    }

    @Test
    fun `an author who only wrote the series has no row of their own`() = runTest(mainRule.dispatcher) {
        val series = listOf(related("13"), related("14"))

        val shown = relatedOf(RelatedBooks(inSeries = series, sameAuthor = series.reversed()))

        assertThat(shown.inSeries).isEqualTo(series)
        assertThat(shown.sameAuthor).isEmpty()
    }

    @Test
    fun `the books of the series are not repeated among those of the author`() = runTest(mainRule.dispatcher) {
        val series = listOf(related("13"), related("14"))

        val shown = relatedOf(RelatedBooks(series, sameAuthor = listOf(related("20"), related("14"), related("21"))))

        assertThat(shown.inSeries).isEqualTo(series)
        assertThat(shown.sameAuthor).containsExactly(related("20"), related("21")).inOrder()
    }

    @Test
    fun `similar books repeat neither the series nor the author`() = runTest(mainRule.dispatcher) {
        val shown = relatedOf(
            RelatedBooks(
                inSeries = listOf(related("13")),
                sameAuthor = listOf(related("13"), related("20")),
                similar = listOf(related("20"), related("30"), related("13"), related("31")),
            ),
        )

        assertThat(shown.sameAuthor).containsExactly(related("20"))
        assertThat(shown.similar).containsExactly(related("30"), related("31")).inOrder()
    }

    @Test
    fun `rows that share no book are left as they are`() = runTest(mainRule.dispatcher) {
        val books = RelatedBooks(listOf(related("13")), listOf(related("20")), listOf(related("30")))

        assertThat(relatedOf(books)).isEqualTo(books)
    }

    @Test
    fun `a row whose call failed hides nothing from the others`() = runTest(mainRule.dispatcher) {
        // The repository leaves the list of a failed call empty.
        val books = RelatedBooks(sameAuthor = listOf(related("13")), similar = listOf(related("30")))

        assertThat(relatedOf(books)).isEqualTo(books)
    }
}
