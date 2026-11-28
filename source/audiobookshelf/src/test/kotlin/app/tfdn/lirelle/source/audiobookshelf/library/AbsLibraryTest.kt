package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.InProgressPath
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.Livres
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedItems
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.routeAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.seriesPath
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private const val Adams = "12e39226-c963-4028-9146-db276d8a0f05"
private const val H2g2 = "4c18e748-4cde-4876-8cac-4d20a40e0da7"
private const val H2g2Series = "216187c2-625f-4ac7-a128-5b09296826f1"
private const val Classiques = "2bffab16-8cc2-41e9-bfb5-fb0640571fcf"

/**
 * An Audiobookshelf profile's library answers each of the library's questions from its server: its libraries, its
 * books, series, authors and shelves, Home's rows, a series', an author's and a book's page, the search and the
 * genres.
 */
class AbsLibraryTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var library: LibraryRepository

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes)
        val allBooks = pagedItems("items-livres-all")
        // A series' books are asked with its filter: the recorded series is the only one answered.
        routes.answer(itemsPath(LivresId)) { request ->
            if (request.url.queryParameter("filter") == null) {
                allBooks(request)
            } else {
                routeAnswer(fixtureBody("items-series-h2g2"))
            }
        }
        routes.answer(seriesPath(LivresId), pagedSeries(recordedSeries()))
        routes.answer("/api/series/$H2g2Series", fixtureBody("series-h2g2"))
        routes.answer("/api/libraries/$LivresId/authors", fixtureBody("authors-livres"))
        routes.answer("/api/authors/$Adams", fixtureBody("author-adams"))
        routes.answer("/api/libraries/$LivresId/collections", fixtureBody("collections-livres"))
        routes.answer("/api/libraries/$BdId/collections", """{"results": [], "total": 0}""")
        routes.answer("/api/collections/$Classiques", fixtureBody("collection-classiques"))
        routes.answer("/api/playlists", fixtureBody("playlists"))
        routes.answer("/api/items/$H2g2", fixtureBody("item-h2g2-expanded"))
        routes.answer(InProgressPath, fixtureBody("items-in-progress"))
        val rig = BrowseRig(server)
        library = AbsLibrary(rig.profileServer, rig.progress, rig.whole)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the lists come from the server`() = runBlocking<Unit> {
        assertThat((library.libraries() as ApiResult.Success).value.map { it.name })
            .containsExactly("Livres", "BD").inOrder()
        assertThat(library.books(BookListSource.AllBooks, BookListRequest(library = Livres)).items.asSnapshot())
            .isNotEmpty()
        assertThat((library.bookPage(BookListRequest(library = Livres), page = 0, size = 5) as ApiResult.Success)
            .value.items).hasSize(5)
        assertThat(library.authors(AuthorSort.Name, SortDirection.Ascending, library = Livres).items.asSnapshot())
            .isNotEmpty()
        assertThat((library.series(library = Livres) as ApiResult.Success).value).isNotEmpty()
        assertThat((library.shelves() as ApiResult.Success).value.collections.map { it.name })
            .containsExactly("Classiques")
        val shelf = BookListSource.Collection(ShelfId("c:$Classiques"))
        assertThat(library.books(shelf, BookListRequest()).items.asSnapshot()).hasSize(3)
        assertThat((library.homeRow(HomeRowKind.Continue) as ApiResult.Success).value.books).hasSize(5)
    }

    @Test
    fun `the pages come from the server`() = runBlocking<Unit> {
        assertThat((library.seriesBooks(SeriesId(H2g2Series)) as ApiResult.Success).value.books).hasSize(7)
        assertThat((library.author(AuthorId(Adams)) as ApiResult.Success).value.author.name)
            .isEqualTo("Douglas Adams")
        assertThat((library.authorBooks(AuthorId(Adams)) as ApiResult.Success).value).isNotEmpty()
        assertThat((library.book(BookId(H2g2)) as ApiResult.Success).value.title)
            .isEqualTo("The Hitchhiker's Guide to the Galaxy")
        assertThat((library.bookReading(BookId(H2g2)) as ApiResult.Success).value.progressPercent).isEqualTo(92)
        assertThat(library.relatedBooks(BookId(H2g2)).inSeries).hasSize(6)
    }

    @Test
    fun `the search and the genres come from the server`() = runBlocking<Unit> {
        routes.answer("/api/libraries/$LivresId/search", fixtureBody("search-herbert"))
        routes.answer("/api/libraries/$BdId/search", """{"book": [], "series": [], "authors": []}""")
        routes.answer("/api/libraries/$LivresId", fixtureBody("filterdata-livres"))
        routes.answer("/api/libraries/$BdId", """{"filterdata": {"genres": []}}""")

        assertThat((library.search("herbert") as ApiResult.Success).value.authors.items.map { it.name })
            .containsExactly("Frank Herbert")
        assertThat((library.genres() as ApiResult.Success).value).hasSize(23)
    }

    @Test
    fun `a list that failed shows the error of its status, as every call with the profile's token`() {
        assertThat(library.errorOf(httpError(403))).isEqualTo(ApiError.NotFound)
        assertThat(library.errorOf(httpError(404))).isEqualTo(ApiError.NotFound)
        assertThat(library.errorOf(httpError(503))).isEqualTo(ApiError.ServerFailed)
    }

    private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "Forbidden".toResponseBody()))
}
