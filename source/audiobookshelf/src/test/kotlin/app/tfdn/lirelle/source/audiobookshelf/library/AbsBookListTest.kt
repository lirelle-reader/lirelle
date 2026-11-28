package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.Livres
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedLibrary
import app.tfdn.lirelle.source.audiobookshelf.testing.bookListOn
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Books list of one library: paged by the server, in the sort asked, while nothing narrows it; with a status, a
 * format or a text, the library's whole list asked once, kept for the profile, and narrowed and sorted on the
 * device; a pull to refresh asks it again.
 */
class AbsBookListTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var list: AbsBookList

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedLibrary(routes)
        list = bookListOn(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `without a filter the server pages the books, newest first, and says how many there are`() = runBlocking<Unit> {
        val books = list.books(BookListRequest(library = Livres))

        val all = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(all).hasSize(40)
        assertThat(all.first().title).isEqualTo("Mostly Harmless")
        // The test server's libraries show square covers.
        assertThat(all.first().coverShape).isEqualTo(CoverShape.Square)
        assertThat(books.total.value).isEqualTo(40)
        val pages = routes.requests(itemsPath(LivresId))
        assertThat(pages.map { it.url.queryParameter("page") }).containsExactlyElementsIn((0..7).map { "$it" })
        pages.forEach { page ->
            assertThat(page.url.queryParameter("limit")).isEqualTo("5")
            assertThat(page.url.queryParameter("sort")).isEqualTo("addedAt")
            assertThat(page.url.queryParameter("desc")).isEqualTo("1")
            assertThat(page.url.queryParameter("filter")).isNull()
        }
    }

    @Test
    fun `each sort is asked of the server by its field, in the direction asked`() = runBlocking<Unit> {
        val asked = listOf(
            BookSort.Added to SortDirection.Ascending,
            BookSort.Title to SortDirection.Ascending,
            BookSort.Author to SortDirection.Descending,
            BookSort.Published to SortDirection.Descending,
            // Audiobookshelf sorts by series only within one: a list asked so is in the order books were added.
            BookSort.Series to SortDirection.Ascending,
        ).map { (sort, direction) ->
            list.bookPage(BookListRequest(sort, direction, library = Livres), page = 0, size = 5)
            val url = routes.requests(itemsPath(LivresId)).last().url
            "${url.queryParameter("sort")} ${url.queryParameter("desc")}"
        }

        assertThat(asked).containsExactly(
            "addedAt 0",
            "media.metadata.title 0",
            "media.metadata.authorNameLF 1",
            "media.metadata.publishedYear 1",
            "addedAt 0",
        ).inOrder()
    }

    @Test
    fun `a status asks the whole library once and keeps its books on the device`() = runBlocking<Unit> {
        val request = BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read)), library = Livres)
        val books = list.books(request)

        val read = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(read.map { it.title }).containsExactly("Life, the Universe and Everything", "Le Petit Prince")
            .inOrder()
        assertThat(read.map { it.readState }.distinct()).containsExactly(ReadState.Read)
        assertThat(books.total.value).isEqualTo(2)
        val whole = routes.requests(itemsPath(LivresId)).single()
        assertThat(whole.url.queryParameter("limit")).isEqualTo("0")
        assertThat(whole.url.queryParameter("filter")).isNull()
    }

    @Test
    fun `several statuses, several formats, and a status with a format narrow as on the other servers`() =
        runBlocking<Unit> {
            suspend fun titles(filters: BookFilters) =
                list.books(BookListRequest(BookSort.Title, SortDirection.Ascending, filters, library = Livres))
                    .items.asSnapshot { appendScrollWhile { true } }.map(BookSummary::title)
            val reading = setOf(StatusFilter.Reading)

            assertThat(titles(BookFilters(statuses = reading + StatusFilter.Read))).hasSize(6)
            assertThat(titles(BookFilters(formats = setOf(FormatFilter.Pdf)))).containsExactly(
                "Causeries historiques sur l'île de la Réunion [fr] (1881)",
                "Parlons Espéranto [fr] (1998)",
            ).inOrder()
            assertThat(titles(BookFilters(formats = setOf(FormatFilter.Audio, FormatFilter.Pdf)))).hasSize(21)
            assertThat(titles(BookFilters(formats = setOf(FormatFilter.Pdf), statuses = reading)))
                .containsExactly("Parlons Espéranto [fr] (1998)")
        }

    @Test
    fun `a text narrows the whole library on the device, its spaces left out`() = runBlocking<Unit> {
        val request = BookListRequest(BookSort.Title, SortDirection.Ascending, text = "  jungers ", library = Livres)
        val books = list.books(request)

        val narrated = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(narrated.map { it.title }).containsExactly(
            "La Maison des mères",
            "Le Bureau des affaires occultes",
            "Le Fantôme du vicaire",
            "Les Hérétiques de Dune",
        ).inOrder()
        assertThat(routes.requests(itemsPath(LivresId)).single().url.queryParameter("limit")).isEqualTo("0")
    }

    @Test
    fun `a status or a format the source does not keep is left out`() = runBlocking<Unit> {
        val unkept = setOf(StatusFilter.WantToRead, StatusFilter.Abandoned)
        val filters = BookFilters(statuses = unkept, readAlongOnly = true)

        list.books(BookListRequest(filters = filters, library = Livres)).items.asSnapshot()

        assertThat(routes.requests(itemsPath(LivresId)).first().url.queryParameter("limit")).isEqualTo("5")
    }

    @Test
    fun `other filters on the same library use the list kept, a pull to refresh asks it again`() = runBlocking<Unit> {
        val read = BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read)), library = Livres)
        val pdf = BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Pdf)), library = Livres)

        list.books(read).items.asSnapshot()
        list.books(pdf).items.asSnapshot()

        assertThat(routes.requests(itemsPath(LivresId))).hasSize(1)
        assertThat(routes.requests("/api/libraries")).hasSize(1)

        // The screens keep a list's pages while they show it: its pager is collected once, and loads again on a
        // pull to refresh.
        list.books(pdf).items.asSnapshot { refresh() }

        assertThat(routes.requests(itemsPath(LivresId))).hasSize(2)
        assertThat(routes.requests(itemsPath(LivresId)).last().url.queryParameter("limit")).isEqualTo("0")
    }

    @Test
    fun `a page without a filter is the server's page, with one the device's slice`() = runBlocking<Unit> {
        val served = list.bookPage(BookListRequest(library = Livres), page = 2, size = 5) as ApiResult.Success

        assertThat(served.value.items).hasSize(5)
        assertThat(served.value.total).isEqualTo(40)
        val asked = routes.requests(itemsPath(LivresId)).single().url
        assertThat(asked.queryParameter("page")).isEqualTo("2")
        assertThat(asked.queryParameter("limit")).isEqualTo("5")

        val audio = BookListRequest(
            BookSort.Title,
            SortDirection.Ascending,
            BookFilters(formats = setOf(FormatFilter.Audio)),
            library = Livres,
        )
        val sliced = list.bookPage(audio, page = 2, size = 5) as ApiResult.Success<PageResult<BookSummary>>

        assertThat(sliced.value.total).isEqualTo(19)
        assertThat(sliced.value.items.map { it.title }).containsExactly(
            "Life, the Universe and Everything",
            "Mostly Harmless",
            "Prélude à Fondation",
            "Project Hail Mary",
            "Red Mars",
        ).inOrder()
    }

    @Test
    fun `a library the account may not see is not found`() = runBlocking<Unit> {
        routes.fail(itemsPath(LivresId), 403)

        val notFound = ApiResult.Failure(ApiError.NotFound)
        assertThat(list.bookPage(BookListRequest(library = Livres), 0, 5)).isEqualTo(notFound)
        val filtered = BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read)), library = Livres)
        assertThat(list.bookPage(filtered, 0, 5)).isEqualTo(notFound)
    }
}
