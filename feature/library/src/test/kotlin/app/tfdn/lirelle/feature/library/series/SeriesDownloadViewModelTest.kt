package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SeriesDownloadViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = SeriesRepository()
    private val downloads = FakeSeriesDownloads()
    private val settings = FakeSeriesSettings()
    private val network = MutableStateFlow(NetworkState(online = true, unmetered = true))
    private val canDownload = MutableStateFlow(true)
    private val kept = FakeKeptData(downloads.calls)

    /** Six books in the order of the series, the second one finished; each a read-along text and an audiobook. */
    private val series = SeriesBooks(
        sampleSeries("4", name = "Dawn", books = 6, read = 1),
        listOf("7", "3", "9", "1", "5", "2")
            .map { sampleBook(it, readState = ReadState.Read.takeIf { _ -> it == "3" }) },
    )

    private fun ids(vararg texts: String) = texts.map(::BookId)

    private fun viewModel(): SeriesDownloadViewModel {
        series.books.forEach { repository.details[it.id] = ApiResult.Success(sampleBookDetail(id = it.id.value)) }
        val surroundings = DownloadSurroundings(network, canDownload, settings) { 41 * Gb }
        return SeriesDownloadViewModel(repository, downloads, surroundings, kept).also { it.show(series) }
    }

    private fun SeriesDownloadViewModel.offers() = (state.value.sheet?.offers as Loadable.Ready).value

    @Test
    fun `the sheet reads the page of each book, four at a time, its rows waiting meanwhile`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            assertThat(viewModel.state.value.sheet).isNull()
            repository.gate = CompletableDeferred()

            viewModel.onButton()

            val sheet = viewModel.state.value.sheet!!
            assertThat(sheet.offers).isEqualTo(Loadable.Loading)
            assertThat(sheet.name).isEqualTo("Dawn")
            assertThat(sheet.books).isEqualTo(6)
            assertThat(sheet.freeBytes).isEqualTo(41 * Gb)
            assertThat(repository.asked).containsExactlyElementsIn(ids("7", "3", "9", "1")).inOrder()

            repository.gate?.complete(Unit)

            assertThat(repository.asked).containsExactlyElementsIn(ids("7", "3", "9", "1", "5", "2")).inOrder()
            val offers = viewModel.offers()
            assertThat(offers.map { it.bookId }).containsExactlyElementsIn(ids("7", "3", "9", "1", "5", "2")).inOrder()
            assertThat(offers.filter { it.finished }.map { it.bookId }).containsExactly(BookId("3"))
            assertThat(offers.first().offered.map { it.format }).containsExactly(Text, Audiobook, Narration).inOrder()
        }

    @Test
    fun `a page that cannot be read fails the sheet, and retry reads them again`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        repository.details[BookId("9")] = ApiResult.Failure(ApiError.ServerUnreachable)

        viewModel.onButton()
        assertThat(viewModel.state.value.sheet?.offers).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))

        repository.details[BookId("9")] = ApiResult.Success(sampleBookDetail(id = "9"))
        viewModel.retry()

        assertThat(viewModel.offers()).hasSize(6)
    }

    @Test
    fun `what the device keeps of a book is not offered again, and the line counts the books that are whole`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            assertThat(viewModel.state.value.onDevice).isNull()

            downloads.kept.value = listOf(
                downloaded("7", record(Text, FormatState.Complete(3 * Mb)), record(Audiobook, FormatState.Queued)),
                downloaded("9", record(Audiobook, FormatState.Downloading(10 * Mb, 400 * Mb))),
                downloaded("40", record(Text, FormatState.Complete(5 * Mb))),
            )
            viewModel.onButton()

            assertThat(viewModel.state.value.onDevice).isEqualTo(SeriesOnDevice(books = 1, of = 6, bytes = 3 * Mb))
            assertThat(viewModel.state.value.sheet?.onDevice).isEqualTo(1)
            assertThat(viewModel.offers().associate { it.bookId to it.kept }).containsExactly(
                BookId("7"), setOf(Text, Audiobook), BookId("3"), emptySet<Any>(), BookId("9"), setOf(Audiobook),
                BookId("1"), emptySet<Any>(), BookId("5"), emptySet<Any>(), BookId("2"), emptySet<Any>(),
            )
        }

    @Test
    fun `a format whose download failed is not kept, it is offered, and asked again`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            val failed = FormatState.Failed(DownloadFailure.Damaged, receivedBytes = Mb)
            downloads.kept.value = listOf(
                downloaded("7", record(Text, FormatState.Complete(3 * Mb)), record(Audiobook, failed)),
                downloaded("9", record(Text, FormatState.Waiting(WaitReason.NoNetwork, 0, 3 * Mb))),
            )
            viewModel.onButton()

            assertThat(viewModel.offers().take(3).associate { it.bookId to it.kept })
                .containsExactly(BookId("7"), setOf(Text), BookId("3"), emptySet<Any>(), BookId("9"), setOf(Text))

            viewModel.confirm(setOf(Text, Audiobook), skipFinished = true)

            assertThat(downloads.calls.take(2)).containsExactly("ask 7 Audiobook", "ask 9 Audiobook").inOrder()
        }

    @Test
    fun `confirming asks the queue book by book in the order of the series, then keeps their pages`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            // The fifth book only has an audiobook; the first already keeps its text.
            val audio = sampleBookDetail(id = "5").let { book ->
                book.copy(media = MediaProfile.AudioOnly, files = book.files.filter { it.kind == BookFileKind.Audio })
            }
            repository.details[BookId("5")] = ApiResult.Success(audio)
            downloads.kept.value = listOf(downloaded("7", record(Text, FormatState.Complete(3 * Mb))))
            viewModel.onButton()

            viewModel.confirm(setOf(Text, Audiobook), skipFinished = true)

            assertThat(downloads.calls).containsExactly(
                "ask 7 Audiobook", "ask 9 Text+Audiobook", "ask 1 Text+Audiobook", "ask 5 Audiobook",
                "ask 2 Text+Audiobook", "keep 7", "keep 9", "keep 1", "keep 5", "keep 2",
            ).inOrder()
            assertThat(downloads.sizes.first()).containsExactly(Audiobook, 310 * Mb)
            assertThat(viewModel.state.value.sheet).isNull()
        }

    @Test
    fun `each book is asked with the name of its own text`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        repository.details[BookId("7")] = ApiResult.Success(sampleComicDetail(id = "7"))
        repository.details[BookId("9")] = ApiResult.Success(samplePagedDetail("PDF", id = "9"))
        viewModel.onButton()

        viewModel.confirm(setOf(Text), skipFinished = true)

        assertThat(downloads.calls.take(3)).containsExactly("ask 7 Text", "ask 9 Text", "ask 1 Text").inOrder()
        assertThat(downloads.names.take(3)).containsExactly(TextName.Cbz, TextName.Pdf, TextName.Text).inOrder()
    }

    @Test
    fun `finished books are asked too once the box is unchecked`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onButton()

        viewModel.confirm(setOf(Audiobook), skipFinished = false)

        assertThat(downloads.calls.filter { it.startsWith("ask") }).containsExactly(
            "ask 7 Audiobook", "ask 3 Audiobook", "ask 9 Audiobook", "ask 1 Audiobook", "ask 5 Audiobook",
            "ask 2 Audiobook",
        ).inOrder()
    }

    @Test
    fun `the choice is remembered and proposed the next time`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onButton()
        assertThat(viewModel.state.value.sheet?.chosen).containsExactly(Text)

        viewModel.confirm(setOf(Text, Narration), skipFinished = true)
        viewModel.onButton()

        assertThat(settings.settings.value.seriesFormats).containsExactly(Text, Narration)
        assertThat(viewModel.state.value.sheet?.chosen).containsExactly(Text, Narration)
    }

    @Test
    fun `the first download asks for the notifications, once`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onButton()
        viewModel.confirm(setOf(Text), skipFinished = true)
        assertThat(viewModel.state.value.askNotifications).isTrue()

        viewModel.notificationsHandled(asked = true)
        assertThat(viewModel.state.value.askNotifications).isFalse()
        assertThat(settings.settings.value.notificationsAsked).isTrue()

        viewModel.onButton()
        viewModel.confirm(setOf(Audiobook), skipFinished = true)
        assertThat(viewModel.state.value.askNotifications).isFalse()
    }

    @Test
    fun `a book whose asking is refused keeps no page, and nothing asked asks for no notification`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            downloads.refused = series.books.mapTo(mutableSetOf()) { it.id }
            viewModel.onButton()

            viewModel.confirm(setOf(Text), skipFinished = true)

            assertThat(downloads.calls.filter { it.startsWith("keep") }).isEmpty()
            assertThat(viewModel.state.value.askNotifications).isFalse()
        }

    @Test
    fun `an account that may not download opens no sheet`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        canDownload.value = false

        viewModel.onButton()

        assertThat(viewModel.state.value.allowed).isFalse()
        assertThat(viewModel.state.value.sheet).isNull()
        assertThat(repository.asked).isEmpty()
    }

    @Test
    fun `on a metered network the settings refuse, the sheet says the downloads will wait`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onButton()
            assertThat(viewModel.state.value.sheet?.waitsForWifi).isFalse()

            network.value = NetworkState(online = true, unmetered = false)
            assertThat(viewModel.state.value.sheet?.waitsForWifi).isTrue()

            settings.update { it.copy(allowMetered = true) }
            assertThat(viewModel.state.value.sheet?.waitsForWifi).isFalse()
        }
}
