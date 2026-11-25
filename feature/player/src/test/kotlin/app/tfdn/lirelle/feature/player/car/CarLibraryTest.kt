package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.feature.player.service.FakeDownloads
import app.tfdn.lirelle.feature.player.service.FakeKeptBookData
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val AnyPage = 100

/** What a car browses of a profile: the rules of each place, over a scripted library and scripted downloads. */
class CarLibraryTest {

    private val library = FakeLibraryRepository()
    private val downloads = FakeDownloads()
    private val kept = FakeKeptBookData()
    private var signInNeeded = false

    /** The time of the clock that does not jump, moved by the tests. */
    private var now = 0L

    private fun car(capabilities: SourceCapabilities = SourceCapabilities.Everything) =
        CarLibrary(library, capabilities, downloads, kept, { now }) { signInNeeded }

    private fun audiobook(id: String, title: String = "Book $id", series: SeriesRef? = null) =
        sampleBook(id, title = title, media = MediaProfile.AudioOnly, series = series)

    private fun readAloud(id: String, title: String = "Book $id") =
        sampleBook(id, title = title, media = MediaProfile.ReadAlong)

    private fun saga(index: String?) = SeriesRef(SeriesId("4"), "A Saga", index)

    private fun row(kind: HomeRowKind, vararg books: BookSummary) {
        library.homeRows += kind to ApiResult.Success(HomeRow(kind, books.toList(), hasMore = false))
    }

    private fun rowFails(kind: HomeRowKind, error: ApiError = ApiError.ServerUnreachable) {
        library.homeRows += kind to ApiResult.Failure(error)
    }

    /** The server's two lists: the books with an audio file, and the read-aloud ones. */
    private fun served(audio: List<BookSummary> = emptyList(), readAloud: List<BookSummary> = emptyList()) {
        library.bookPageAnswer = { request, page, size ->
            val list = if (request.filters.readAlongOnly) readAloud else audio
            ApiResult.Success(PageResult(list.drop(page * size).take(size), list.size))
        }
    }

    private suspend fun CarLibrary.nodes(place: CarPlace, page: Int = 0, pageSize: Int = AnyPage): List<CarNode> =
        (children(place, page, pageSize) as CarAnswer.Nodes).nodes

    private fun List<CarNode>.bookIds(): List<String> = map { (it as CarNode.Book).id.value }

    // --- what can be listened to

    @Test
    fun `a book with an audio file is listened to where the source has audiobooks`() {
        val book = sampleBook("1", media = MediaProfile.TextAndAudio)

        assertThat(listenable(book, SourceCapabilities.Everything)).isTrue()
        assertThat(listenable(book, SourceCapabilities.Everything.copy(audiobooks = false))).isFalse()
    }

    @Test
    fun `a read-aloud book is listened to where the source has read-aloud books`() {
        val book = sampleBook("1", media = MediaProfile.ReadAlong)

        assertThat(listenable(book, SourceCapabilities.Everything)).isTrue()
        assertThat(listenable(book, SourceCapabilities.Everything.copy(readAlong = false))).isFalse()
    }

    @Test
    fun `a book of text only is never listened to`() {
        assertThat(listenable(sampleBook("1"), SourceCapabilities.Everything)).isFalse()
    }

    @Test
    fun `a source without audio has nothing to listen to in any tab, and its server is asked nothing`() = runTest {
        val car = car(KomgaLikeCapabilities)

        val answers = CarPlace.Tab.entries.map { car.children(it, 0, AnyPage) } +
            car.children(CarPlace.OneSeries(SeriesId("4")), 0, AnyPage) +
            car.search("road")

        assertThat(answers.toSet()).containsExactly(CarAnswer.Notice(CarNotice.NothingToListen))
        assertThat(library.calls).isEmpty()
    }

    // --- the root

    @Test
    fun `the root lists the four tabs in order, whatever the profile`() = runTest {
        signInNeeded = true
        val tabs = listOf(CarPlace.Tab.Home, CarPlace.Tab.Downloaded, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)

        assertThat(car().nodes(CarPlace.Root)).isEqualTo(tabs.map(CarNode::Tab))
        assertThat(car(KomgaLikeCapabilities).nodes(CarPlace.Root)).isEqualTo(tabs.map(CarNode::Tab))
    }

    // --- Home

    @Test
    fun `Home lists the three rows as sections, with what can be listened to only`() = runTest {
        row(HomeRowKind.Continue, audiobook("1"), sampleBook("2"))
        row(HomeRowKind.UpNext, audiobook("3", series = saga("2")))
        row(HomeRowKind.RecentlyAdded, readAloud("4"), sampleBook("5"))

        val nodes = car().nodes(CarPlace.Tab.Home)

        assertThat(nodes).containsExactly(
            CarNode.Book(BookId("1"), "Book 1", CarLine.Author("Author 1"), CarSection.Continue),
            CarNode.Book(BookId("3"), "Book 3", CarLine.InSeries("A Saga", "2"), CarSection.UpNext),
            CarNode.Book(BookId("4"), "Book 4", CarLine.Author("Author 4"), CarSection.RecentlyAdded),
        ).inOrder()
        assertThat(library.calls).containsExactly(
            "homeRow Continue SeeAll",
            "homeRow UpNext SeeAll",
            "homeRow RecentlyAdded SeeAll",
        )
    }

    @Test
    fun `a book to continue says how much is left when its card knows, else its author`() = runTest {
        row(HomeRowKind.Continue, audiobook("1").copy(timeLeftSeconds = 90), audiobook("2"))

        val lines = car().nodes(CarPlace.Tab.Home).map { (it as CarNode.Book).line }

        assertThat(lines).containsExactly(CarLine.Left(90_000), CarLine.Author("Author 2")).inOrder()
    }

    @Test
    fun `a next volume outside a series, and a book without an author, say what they can`() = runTest {
        row(HomeRowKind.UpNext, audiobook("1"))
        row(HomeRowKind.RecentlyAdded, audiobook("2").copy(authors = emptyList()))

        val lines = car().nodes(CarPlace.Tab.Home).map { (it as CarNode.Book).line }

        assertThat(lines).containsExactly(CarLine.Author("Author 1"), null).inOrder()
    }

    @Test
    fun `a book of several Home rows shows in the first only`() = runTest {
        row(HomeRowKind.Continue, audiobook("1"))
        row(HomeRowKind.UpNext, audiobook("2"))
        row(HomeRowKind.RecentlyAdded, audiobook("2"), audiobook("1"), audiobook("3"))

        val nodes = car().nodes(CarPlace.Tab.Home)

        assertThat(nodes.bookIds()).containsExactly("1", "2", "3").inOrder()
        assertThat(nodes.map { (it as CarNode.Book).section })
            .containsExactly(CarSection.Continue, CarSection.UpNext, CarSection.RecentlyAdded).inOrder()
    }

    @Test
    fun `a Home row that fails is left out`() = runTest {
        rowFails(HomeRowKind.Continue)
        row(HomeRowKind.RecentlyAdded, audiobook("4"))

        assertThat(car().nodes(CarPlace.Tab.Home).bookIds()).containsExactly("4")
    }

    @Test
    fun `Home is unreachable when every row fails`() = runTest {
        rowFails(HomeRowKind.Continue)
        rowFails(HomeRowKind.UpNext, ApiError.ServerFailed)
        rowFails(HomeRowKind.RecentlyAdded)

        assertThat(car().children(CarPlace.Tab.Home, 0, AnyPage)).isEqualTo(CarAnswer.Notice(CarNotice.Unreachable))
    }

    @Test
    fun `Home is one page, whatever its size`() = runTest {
        row(HomeRowKind.Continue, audiobook("1"), audiobook("2"), audiobook("3"))

        assertThat(car().nodes(CarPlace.Tab.Home, page = 0, pageSize = 2)).hasSize(3)
        assertThat(car().nodes(CarPlace.Tab.Home, page = 1, pageSize = 2)).isEmpty()
    }

    @Test
    fun `Home asks only the rows its source has`() = runTest {
        val capabilities = SourceCapabilities.Everything.copy(homeRows = setOf(HomeRowKind.Continue))
        row(HomeRowKind.Continue, audiobook("1"))

        assertThat(car(capabilities).nodes(CarPlace.Tab.Home).bookIds()).containsExactly("1")
        assertThat(library.calls).containsExactly("homeRow Continue SeeAll")
    }

    // --- Downloaded

    private fun keep(id: String, format: DownloadFormat, title: String, openedAt: String? = null) {
        downloads.has(BookId(id), format, openedAt = openedAt?.let(Instant::parse))
        kept.details[BookId(id)] = sampleBookDetail(id).copy(title = title)
    }

    @Test
    fun `Downloaded lists the audiobooks and narrations kept whole, the last opened first`() = runTest {
        keep("1", DownloadFormat.Audiobook, "Kept first", openedAt = "2026-11-20T10:00:00Z")
        keep("2", DownloadFormat.Narration, "Opened last", openedAt = "2026-11-22T10:00:00Z")
        keep("3", DownloadFormat.Text, "A text")
        keep("4", DownloadFormat.Audiobook, "Never opened")

        val nodes = car().nodes(CarPlace.Tab.Downloaded)

        assertThat(nodes).containsExactly(
            CarNode.Book(BookId("2"), "Opened last", CarLine.Author("Ursula Writer")),
            CarNode.Book(BookId("1"), "Kept first", CarLine.Author("Ursula Writer")),
            // Dated by its download, which the fake sets before both openings.
            CarNode.Book(BookId("4"), "Never opened", CarLine.Author("Ursula Writer")),
        ).inOrder()
    }

    @Test
    fun `Downloaded leaves out what is not whole, what is being deleted and what has no page kept`() = runTest {
        keep("1", DownloadFormat.Audiobook, "Whole")
        keep("2", DownloadFormat.Audiobook, "Half")
        downloads.has(BookId("2"), DownloadFormat.Audiobook, FormatState.Downloading(10, 100))
        keep("3", DownloadFormat.Audiobook, "Going")
        downloads.has(BookId("3"), DownloadFormat.Audiobook, removing = true)
        downloads.has(BookId("4"), DownloadFormat.Audiobook)

        assertThat(car().nodes(CarPlace.Tab.Downloaded).bookIds()).containsExactly("1")
    }

    @Test
    fun `Downloaded asks the server nothing, and answers while a sign-in is needed`() = runTest {
        keep("1", DownloadFormat.Audiobook, "Whole")
        signInNeeded = true

        assertThat(car().nodes(CarPlace.Tab.Downloaded).bookIds()).containsExactly("1")
        assertThat(library.calls).isEmpty()
    }

    @Test
    fun `Downloaded follows the page asked`() = runTest {
        keep("1", DownloadFormat.Audiobook, "One", openedAt = "2026-11-23T10:00:00Z")
        keep("2", DownloadFormat.Audiobook, "Two", openedAt = "2026-11-22T10:00:00Z")
        keep("3", DownloadFormat.Audiobook, "Three", openedAt = "2026-11-21T10:00:00Z")

        assertThat(car().nodes(CarPlace.Tab.Downloaded, page = 1, pageSize = 2).bookIds()).containsExactly("3")
    }

    // --- Audiobooks

    @Test
    fun `Audiobooks merges the books with audio and the read-aloud ones, by title, each once`() = runTest {
        val both = sampleBook("3", title = "Both", media = MediaProfile.ReadAlong).copy(hasAudioFiles = true)
        served(
            audio = listOf(audiobook("1", "Alpha"), both, audiobook("2", "émile")),
            readAloud = listOf(both, readAloud("4", "Delta")),
        )

        val nodes = car().nodes(CarPlace.Tab.Audiobooks)

        assertThat(nodes.bookIds()).containsExactly("1", "3", "4", "2").inOrder()
        assertThat(nodes.first()).isEqualTo(CarNode.Book(BookId("1"), "Alpha", CarLine.Author("Author 1")))
    }

    @Test
    fun `Audiobooks asks each kind by title, filtered by the server`() = runTest {
        served()

        car().nodes(CarPlace.Tab.Audiobooks)

        val byTitle = BookListRequest(sort = BookSort.Title, direction = SortDirection.Ascending)
        assertThat(library.bookPageRequests.map { it.first }).containsExactly(
            byTitle.copy(filters = BookFilters(formats = setOf(FormatFilter.Audio))),
            byTitle.copy(filters = BookFilters(readAlongOnly = true)),
        )
    }

    @Test
    fun `Audiobooks asks only the kinds its source has`() = runTest {
        served(audio = listOf(audiobook("1")), readAloud = listOf(readAloud("2")))

        val nodes = car(SourceCapabilities.Everything.copy(readAlong = false)).nodes(CarPlace.Tab.Audiobooks)

        assertThat(nodes.bookIds()).containsExactly("1")
        assertThat(library.bookPageRequests.map { it.first.filters })
            .containsExactly(BookFilters(formats = setOf(FormatFilter.Audio)))
    }

    @Test
    fun `Audiobooks leaves out a book the server lists that cannot be listened to`() = runTest {
        served(audio = listOf(audiobook("1"), sampleBook("2")))

        assertThat(car().nodes(CarPlace.Tab.Audiobooks).bookIds()).containsExactly("1")
    }

    @Test
    fun `Audiobooks reads every page of the server's lists and follows the page asked`() = runTest {
        // Titles that sort as their numbers do.
        served(audio = (100 until 250).map { audiobook("$it") })

        val car = car()

        assertThat(car.nodes(CarPlace.Tab.Audiobooks, page = 0, pageSize = 100).bookIds())
            .isEqualTo((100 until 200).map { "$it" })
        assertThat(car.nodes(CarPlace.Tab.Audiobooks, page = 1, pageSize = 100).bookIds())
            .isEqualTo((200 until 250).map { "$it" })
        assertThat(car.nodes(CarPlace.Tab.Audiobooks, page = 2, pageSize = 100)).isEmpty()
    }

    @Test
    fun `Audiobooks stops at six hundred books of a kind`() = runTest {
        served(audio = (1000 until 1700).map { audiobook("$it") })

        assertThat(car().nodes(CarPlace.Tab.Audiobooks, pageSize = 1_000)).hasSize(ListenableBound)
    }

    // --- Series

    @Test
    fun `Series lists the series that have a book to listen to, by name, with how many`() = runTest {
        val other = SeriesRef(SeriesId("9"), "Zebra", "1")
        served(
            audio = listOf(
                audiobook("1", series = other),
                audiobook("2", series = saga("1")),
                audiobook("3"),
                audiobook("4", series = SeriesRef(null, "Unnamed", "1")),
            ),
            readAloud = listOf(readAloud("5").copy(series = saga("2"))),
        )

        assertThat(car().nodes(CarPlace.Tab.Series)).containsExactly(
            CarNode.Series(SeriesId("4"), "A Saga", books = 2),
            CarNode.Series(SeriesId("9"), "Zebra", books = 1),
        ).inOrder()
    }

    @Test
    fun `a series lists its books to listen to in its own order, each with its number`() = runTest {
        val books = listOf(audiobook("2", series = saga("1")), sampleBook("3"), audiobook("1", series = saga("2")))
        library.seriesBooksResult = ApiResult.Success(SeriesBooks(sampleSeries("4"), books))

        val nodes = car().nodes(CarPlace.OneSeries(SeriesId("4")))

        assertThat(nodes).containsExactly(
            CarNode.Book(BookId("2"), "Book 2", CarLine.InSeries("A Saga", "1")),
            CarNode.Book(BookId("1"), "Book 1", CarLine.InSeries("A Saga", "2")),
        ).inOrder()
        assertThat(library.calls).containsExactly("seriesBooks 4")
    }

    // --- a sign-in needed, a server out of reach

    @Test
    fun `while a sign-in is needed, every place but Downloaded and the root says so`() = runTest {
        signInNeeded = true
        val car = car()

        val answers = listOf(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)
            .map { car.children(it, 0, AnyPage) } +
            car.children(CarPlace.OneSeries(SeriesId("4")), 0, AnyPage) +
            car.search("road")

        assertThat(answers.toSet()).containsExactly(CarAnswer.Notice(CarNotice.SignInNeeded))
        assertThat(library.calls).isEmpty()
    }

    @Test
    fun `a tab whose server cannot be reached says so`() = runTest {
        library.bookPageAnswer = { _, _, _ -> ApiResult.Failure(ApiError.ServerUnreachable) }
        library.seriesBooksResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val car = car()

        val answers = listOf(CarPlace.Tab.Audiobooks, CarPlace.Tab.Series, CarPlace.OneSeries(SeriesId("4")))
            .map { car.children(it, 0, AnyPage) } + car.search("road")

        assertThat(answers.toSet()).containsExactly(CarAnswer.Notice(CarNotice.Unreachable))
    }

    @Test
    fun `any other failure is said as a server out of reach`() = runTest {
        library.bookPageAnswer = { request, _, _ ->
            // One of the two lists fails: the tab does not show half of the books.
            if (request.filters.readAlongOnly) ApiResult.Failure(ApiError.ServerFailed) else null
        }
        library.seriesBooksResult = ApiResult.Failure(ApiError.NotFound)
        val car = car()

        assertThat(car.children(CarPlace.Tab.Audiobooks, 0, AnyPage))
            .isEqualTo(CarAnswer.Notice(CarNotice.Unreachable))
        assertThat(car.children(CarPlace.OneSeries(SeriesId("4")), 0, AnyPage))
            .isEqualTo(CarAnswer.Notice(CarNotice.Unreachable))
    }

    // --- the audio library, kept a short time

    @Test
    fun `the pages of one visit, and Series right after Audiobooks, read the server's lists once`() = runTest {
        served(audio = listOf(audiobook("1", series = saga("1"))), readAloud = listOf(readAloud("2")))
        val car = car()

        car.nodes(CarPlace.Tab.Audiobooks, page = 0, pageSize = 1)
        val asked = library.bookPageRequests.size
        now = ListenableFreshMillis - 1
        val second = car.nodes(CarPlace.Tab.Audiobooks, page = 1, pageSize = 1)
        val series = car.nodes(CarPlace.Tab.Series)

        assertThat(second.bookIds()).containsExactly("2")
        assertThat(series).containsExactly(CarNode.Series(SeriesId("4"), "A Saga", 1))
        assertThat(asked).isEqualTo(2)
        assertThat(library.bookPageRequests).hasSize(asked)
    }

    @Test
    fun `two minutes later the audio library is read again`() = runTest {
        served(audio = listOf(audiobook("1")))
        val car = car()

        car.nodes(CarPlace.Tab.Audiobooks)
        served(audio = listOf(audiobook("1"), audiobook("2")))
        now = ListenableFreshMillis

        assertThat(car.nodes(CarPlace.Tab.Audiobooks).bookIds()).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `a read that failed is not kept`() = runTest {
        library.bookPageAnswer = { _, _, _ -> ApiResult.Failure(ApiError.ServerUnreachable) }
        val car = car()

        assertThat(car.children(CarPlace.Tab.Audiobooks, 0, AnyPage))
            .isEqualTo(CarAnswer.Notice(CarNotice.Unreachable))
        served(audio = listOf(audiobook("1")))

        assertThat(car.nodes(CarPlace.Tab.Series)).isEmpty()
        assertThat(car.nodes(CarPlace.Tab.Audiobooks).bookIds()).containsExactly("1")
    }

    @Test
    fun `the books whose audio the device keeps are told as they change`() = runTest {
        downloads.has(BookId("1"), DownloadFormat.Audiobook)
        downloads.has(BookId("2"), DownloadFormat.Text)
        downloads.has(BookId("3"), DownloadFormat.Narration, state = FormatState.Queued)
        val car = car()

        assertThat(car.keptAudio.first()).containsExactly(BookId("1"))
        downloads.has(BookId("3"), DownloadFormat.Narration)
        assertThat(car.keptAudio.first()).containsExactly(BookId("1"), BookId("3"))
    }

    // --- search

    @Test
    fun `a search asks the books matching the text and keeps those to listen to`() = runTest {
        library.books = listOf(audiobook("1"), sampleBook("2"), readAloud("3"))

        val answer = car().search(" road ") as CarAnswer.Nodes

        assertThat(answer.nodes).containsExactly(
            CarNode.Book(BookId("1"), "Book 1", CarLine.Author("Author 1")),
            CarNode.Book(BookId("3"), "Book 3", CarLine.Author("Author 3")),
        ).inOrder()
        assertThat(library.bookPageRequests).containsExactly(BookListRequest(text = "road") to 0)
    }

    @Test
    fun `a blank search finds nothing, and asks nothing`() = runTest {
        assertThat(car().search("  ")).isEqualTo(CarAnswer.Nodes(emptyList()))
        assertThat(library.calls).isEmpty()
    }
}
