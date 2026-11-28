package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedLibrary
import app.tfdn.lirelle.source.audiobookshelf.testing.bookListOn
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.seriesPath
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The lists given no library, as Search's "See all" asks them: every book library's, joined in the server's order
 * of the libraries, the podcast library left out. With a library, each list is that library's alone (the other
 * tests of each list).
 */
class AbsEveryLibraryTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedLibrary(routes)
    }

    @After
    fun tearDown() {
        assertThat(routes.requests().map { it.url.encodedPath }.filter { PodcastsId in it }).isEmpty()
        server.close()
    }

    @Test
    fun `the Books list given no library pages each book library in turn`() = runBlocking<Unit> {
        val books = bookListOn(server).books(BookListRequest())

        val all = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(all).hasSize(45)
        assertThat(all.drop(40).map { it.title }).containsExactlyElementsIn(BdTitles).inOrder()
        assertThat(books.total.value).isEqualTo(45)
        val firstBd = routes.requests().indexOf(routes.requests(itemsPath(BdId)).first())
        assertThat(routes.requests(itemsPath(LivresId)).all { routes.requests().indexOf(it) < firstBd }).isTrue()
        (routes.requests(itemsPath(LivresId)) + routes.requests(itemsPath(BdId))).forEach { page ->
            assertThat(page.url.queryParameter("sort")).isEqualTo("addedAt")
            assertThat(page.url.queryParameter("desc")).isEqualTo("1")
        }
    }

    @Test
    fun `a page of the Books list given no library follows the same order`() = runBlocking<Unit> {
        val page = bookListOn(server).bookPage(BookListRequest(), page = 8, size = 5) as ApiResult.Success

        assertThat(page.value.total).isEqualTo(45)
        assertThat(page.value.items.map { it.title }).containsExactlyElementsIn(BdTitles).inOrder()
    }

    @Test
    fun `a narrowed Books list given no library narrows every book library's whole list, sorted together`() =
        runBlocking<Unit> {
            val formats = setOf(FormatFilter.Pdf, FormatFilter.Comics)
            val request = BookListRequest(BookSort.Title, SortDirection.Descending, BookFilters(formats = formats))

            val books = bookListOn(server).books(request).items.asSnapshot { appendScrollWhile { true } }

            assertThat(books.map { it.title }).containsExactlyElementsIn(
                listOf("Parlons Espéranto [fr] (1998)", "Causeries historiques sur l'île de la Réunion [fr] (1881)") +
                    BdTitles.reversed(),
            ).inOrder()
            assertThat(routes.requests(itemsPath(LivresId)).single().url.queryParameter("limit")).isEqualTo("0")
            assertThat(routes.requests(itemsPath(BdId)).single().url.queryParameter("limit")).isEqualTo("0")
        }

    @Test
    fun `the series given no library are every book library's, joined in their order, each once`() =
        runBlocking<Unit> {
            val livres = recordedSeries()
            val asterix = livres.first().jsonObject
                .with("id", JsonPrimitive("asterix-series"))
                .with("name", JsonPrimitive("Astérix"))
            routes.answer(seriesPath(LivresId), pagedSeries(livres))
            routes.answer(seriesPath(BdId), pagedSeries(JsonArray(listOf(asterix, livres.first()))))
            val rig = BrowseRig(server)

            val series = AbsSeries(rig.profileServer, rig.progress, rig.whole).series(text = null, library = null)

            assertThat((series as ApiResult.Success).value.map(Series::name)).containsExactly(
                "A Game of Thrones",
                "A Series of Unfortunate Events",
                "Cantos d'Hyperion",
                "Charlie",
                "Chronique du tueur de roi",
                "Astérix",
            ).inOrder()
        }

    @Test
    fun `the authors given no library are every book library's, sorted together`() = runBlocking<Unit> {
        routes.answer(authorsPath(LivresId), fixtureBody("authors-livres"))
        routes.answer(
            authorsPath(BdId),
            """{"authors":[{"id":"goscinny","name":"René Goscinny","lastFirst":"Goscinny, René","numBooks":3}]}""",
        )
        val rig = BrowseRig(server)
        val authors = AbsAuthors(rig.profileServer, rig.progress, rig.whole, pageSize = 4)
            .authors(AuthorSort.Name, SortDirection.Ascending, text = null, library = null)

        val names = authors.items.asSnapshot { appendScrollWhile { true } }.map(Author::name)

        assertThat(names).hasSize(11)
        assertThat(names.take(3)).containsExactly("Douglas Adams", "Antoine de Saint-Exupéry", "René Goscinny")
            .inOrder()
    }
}

/** The recorded server's podcast library, which no list asks for. */
private const val PodcastsId = "2c073bf4-c0cb-4380-b775-efdbf060af02"

/** The recorded BD list's books, in the server's order. */
private val BdTitles = listOf("Aldebaran 1", "Alix 1", "Alix Senator 1", "Amazonie 1", "Antarès 1")

private fun authorsPath(libraryId: String): String = "/api/libraries/$libraryId/authors"
