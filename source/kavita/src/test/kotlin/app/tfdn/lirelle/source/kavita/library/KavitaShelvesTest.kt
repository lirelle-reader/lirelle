package app.tfdn.lirelle.source.kavita.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.FakeTitles
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.RecordedOn
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.json
import app.tfdn.lirelle.source.kavita.testing.route
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private val WinterList = BookListSource.Collection(ShelfId("7"))

/** The ids of the recorded reading list's books, in its order. */
private val ListOrder = listOf("v407", "v406", "c899", "c915", "c896").map(::BookId)

/**
 * A Kavita profile's shelves are its reading lists, those others promoted among them, shown as collections; a list
 * opens on its books in its order, asked in one call, sorted and narrowed on the device.
 */
class KavitaShelvesTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var library: KavitaLibrary

    @Before
    fun setUp() {
        routes = KavitaRoutes(server)
        server.start()
        library = routes.library()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private suspend fun books(request: BookListRequest = BookListRequest()) =
        library.books(WinterList, request).items.asSnapshot()

    @Test
    fun `the reading lists are the shelves, each a collection with its number of books`() = runBlocking<Unit> {
        routes.answerFixture("POST /api/ReadingList/lists", "readinglists")

        assertThat(library.shelves()).isEqualTo(
            ApiResult.Success(
                Shelves(
                    smartLists = emptyList(),
                    collections = listOf(Shelf(ShelfKind.Collection, ShelfId("7"), "À lire cet hiver", 5)),
                ),
            ),
        )
        val asked = routes.requests().single()
        assertThat(asked.route()).isEqualTo("POST /api/ReadingList/lists")
        assertThat(asked.url.encodedQuery)
            .isEqualTo("PageNumber=1&PageSize=100&includePromoted=true&sortByLastModified=false")
    }

    @Test
    fun `the reading lists of the oldest Kavita supported are read the same`() = runBlocking<Unit> {
        routes.answerFixture("POST /api/ReadingList/lists", "readinglists", RecordedOn.Minimum)

        val shelves = (library.shelves() as ApiResult.Success).value

        assertThat(shelves.collections)
            .containsExactly(Shelf(ShelfKind.Collection, ShelfId("6"), "À lire cet hiver", 5))
    }

    @Test
    fun `a list opens on its books in its order, asked in one call`() = runBlocking<Unit> {
        routes.answerFixture("GET /api/ReadingList/items", "readinglist-items")

        val list = library.books(WinterList, BookListRequest())
        val books = list.items.asSnapshot()

        val mapper = KavitaMapper(server.serverUrl, FakeTitles)
        val items = KavitaJson.decodeFromString<List<ReadingListItemDto>>(fixtureBody("readinglist-items"))
        assertThat(books).containsExactlyElementsIn(items.map(mapper::readingListItem)).inOrder()
        assertThat(books.map { it.id }).containsExactlyElementsIn(ListOrder).inOrder()
        assertThat(list.total.value).isEqualTo(5)
        val asked = routes.requests().single()
        assertThat(asked.route()).isEqualTo("GET /api/ReadingList/items")
        assertThat(asked.url.queryParameter("readingListId")).isEqualTo("7")
    }

    @Test
    fun `a list refreshed is asked again`() = runBlocking<Unit> {
        var asked = 0
        routes.answer("GET /api/ReadingList/items") {
            asked++
            json(if (asked == 1) fixtureBody("readinglist-items") else """[{"chapterId":9,"chapterNumber":"9"}]""")
        }

        val books = library.books(WinterList, BookListRequest()).items.asSnapshot { refresh() }

        assertThat(books.map { it.id }).containsExactly(BookId("c9"))
        assertThat(routes.requests().map { it.route() }).containsExactly(
            "GET /api/ReadingList/items",
            "GET /api/ReadingList/items",
        )
    }

    @Test
    fun `a list's books are sorted by title on the device, or in its order turned around`() = runBlocking<Unit> {
        routes.answerFixture("GET /api/ReadingList/items", "readinglist-items")

        val byTitle = books(BookListRequest(sort = BookSort.Title, direction = SortDirection.Ascending))
        val backwards = books(BookListRequest(sort = BookSort.Title, direction = SortDirection.Descending))
        val oldestFirst = books(BookListRequest(sort = BookSort.Added, direction = SortDirection.Ascending))

        val titles = listOf("Alix L'Intrépide", "Dune", "L'Île Maudite", "Le Messie de Dune", "Le Sphinx d'Or")
        assertThat(byTitle.map { it.title }).containsExactlyElementsIn(titles).inOrder()
        assertThat(backwards.map { it.title }).containsExactlyElementsIn(titles.reversed()).inOrder()
        assertThat(oldestFirst.map { it.id }).containsExactlyElementsIn(ListOrder.reversed()).inOrder()
    }

    @Test
    fun `a list's books are narrowed to formats on the device`() = runBlocking<Unit> {
        routes.answerFixture("GET /api/ReadingList/items", "readinglist-items")

        val comics = books(BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Comics))))
        val epubs = books(BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Epub))))
        val pdfs = books(BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Pdf))))

        assertThat(comics.map { it.id }).containsExactly(BookId("c899"), BookId("c915"), BookId("c896")).inOrder()
        assertThat(epubs.map { it.id }).containsExactly(BookId("v407"), BookId("v406")).inOrder()
        assertThat(pdfs).isEmpty()
    }

    @Test
    fun `a list's books are narrowed to reading states on the device`() = runBlocking<Unit> {
        routes.answer(
            "GET /api/ReadingList/items",
            """[{"chapterId":1,"seriesName":"S","chapterNumber":"1","pagesRead":0,"pagesTotal":10},""" +
                """{"chapterId":2,"seriesName":"S","chapterNumber":"2","pagesRead":5,"pagesTotal":10},""" +
                """{"chapterId":3,"seriesName":"S","chapterNumber":"3","pagesRead":10,"pagesTotal":10}]""",
        )

        fun statuses(vararg statuses: StatusFilter) =
            BookListRequest(filters = BookFilters(statuses = statuses.toSet()))

        assertThat(books(statuses(StatusFilter.Unread)).map { it.id }).containsExactly(BookId("c1"))
        assertThat(books(statuses(StatusFilter.Reading)).map { it.id }).containsExactly(BookId("c2"))
        assertThat(books(statuses(StatusFilter.Read, StatusFilter.Unread)).map { it.id })
            .containsExactly(BookId("c1"), BookId("c3")).inOrder()
        assertThat(books(statuses(StatusFilter.WantToRead))).isEmpty()
    }

    @Test
    fun `a list that fails to load is a load error the screen can tell`() = runBlocking<Unit> {
        routes.fail("GET /api/ReadingList/items", 401)

        val thrown = assertThrows(ApiErrorException::class.java) { runBlocking { books() } }

        assertThat(thrown.error).isEqualTo(ApiError.SessionExpired)
        assertThat(library.errorOf(thrown)).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `there is no list of all books nor smart lists, and nothing is asked`() = runBlocking<Unit> {
        val all = library.books(BookListSource.AllBooks, BookListRequest())
        val smart = library.books(BookListSource.SmartList(ShelfId("3")), BookListRequest())

        assertThat(all.items.asSnapshot()).isEmpty()
        assertThat(all.total.value).isEqualTo(0)
        assertThat(smart.items.asSnapshot()).isEmpty()
        assertThat((library.bookPage(BookListRequest(), page = 0, size = 60) as ApiResult.Success).value.items)
            .isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a key the server refuses is a session that ended`() = runBlocking<Unit> {
        routes.fail("POST /api/ReadingList/lists", 401)

        assertThat(library.shelves()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }
}
