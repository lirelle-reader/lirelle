package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.bdId
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.livresId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Asterix = "9bbf1b05-7db2-4018-8225-753eadee8fac"
private const val AsterixSeries = "accf4baa-ab42-43f1-bc04-36e3f4e7295e"
private const val Herbert = "f71dd78d-d90a-4750-af66-8e76d0ad31d7"
private const val PodcastsId = "2c073bf4-c0cb-4380-b775-efdbf060af02"

/** What a library's search answers when it finds nothing. */
private const val NothingFound = """{"book": [], "series": [], "authors": []}"""

/**
 * The search: each book library's search, asked at once, joined in the libraries' order; a library the account may
 * not search left out; nothing asked for a blank text.
 */
class AbsSearchTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var search: AbsSearch

    @Before
    fun setUp() {
        server.start()
        answer(RecordedOn.Latest)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** The account and the libraries recorded on [on], whose searches find nothing until a test says otherwise. */
    private fun answer(on: RecordedOn) {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        routes.answer(searchPath(livresId(on)), NothingFound)
        routes.answer(searchPath(bdId(on)), NothingFound)
        val rig = BrowseRig(server)
        search = AbsSearch(rig.profileServer, rig.progress, rig.whole)
    }

    private suspend fun found(text: String): SearchResults = (search.search(text) as ApiResult.Success).value

    @Test
    fun `each book library is searched, and what they find is joined`() = runBlocking<Unit> {
        routes.answer(searchPath(bdId(RecordedOn.Latest)), fixtureBody("search-asterix"))

        val found = found("astérix")

        assertThat(found.text).isEqualTo("astérix")
        val asterix = found.books.items.single()
        assertThat(asterix.id).isEqualTo(BookId(Asterix))
        assertThat(asterix.title).isEqualTo("Astérix 1")
        assertThat(asterix.pagedKind).isEqualTo(PagedKind.Comic)
        assertThat(asterix.readState).isEqualTo(ReadState.Reading)
        assertThat(asterix.progressPercent).isEqualTo(35)
        assertThat(asterix.coverUrl).contains("/api/items/$Asterix/cover")
        val series = found.series.items.single()
        assertThat(series.id).isEqualTo(SeriesId(AsterixSeries))
        assertThat(series.name).isEqualTo("Astérix")
        assertThat(series.bookCount).isEqualTo(1)
        assertThat(series.coverUrls.single()).contains("/api/items/$Asterix/cover")
        assertThat(found.authors.items).isEmpty()
        for (library in listOf(livresId(RecordedOn.Latest), bdId(RecordedOn.Latest))) {
            val request = routes.requests(searchPath(library)).single()
            assertThat(request.url.encodedQuery).contains("q=ast%C3%A9rix")
            assertThat(request.url.queryParameter("limit")).isEqualTo("12")
        }
        assertThat(routes.requests(searchPath(PodcastsId))).isEmpty()
    }

    @Test
    fun `a full group of books or series counts one more, so that See all shows`() = runBlocking<Unit> {
        routes.answer(searchPath(livresId(RecordedOn.Latest)), fixtureBody("search-potter"))

        val found = found("potter")

        assertThat(found.books.items).hasSize(12)
        assertThat(found.books.items.first().title).isEqualTo("Harry Potter et les Reliques de la Mort")
        assertThat(found.books.total).isEqualTo(13)
        assertThat(found.series.items.map { it.name })
            .containsExactly("Harry Potter (fr)", "Harry Potter (en)", "Harry Potter 7 3/4").inOrder()
        assertThat(found.series.items.map { it.bookCount }).containsExactly(9, 8, 4).inOrder()
        assertThat(found.series.items.first().coverUrls).hasSize(3)
        assertThat(found.series.total).isEqualTo(3)
    }

    @Test
    fun `an author's name finds the author, with the author's number of books`() = runBlocking<Unit> {
        routes.answer(searchPath(livresId(RecordedOn.Latest)), fixtureBody("search-herbert"))

        val found = found("herbert")

        assertThat(found.books.items).isEmpty()
        val herbert = found.authors.items.single()
        assertThat(herbert.id).isEqualTo(AuthorId(Herbert))
        assertThat(herbert.name).isEqualTo("Frank Herbert")
        assertThat(herbert.bookCount).isEqualTo(17)
        assertThat(found.authors.total).isEqualTo(1)
    }

    @Test
    fun `what two libraries both find shows once`() = runBlocking<Unit> {
        routes.answer(searchPath(livresId(RecordedOn.Latest)), fixtureBody("search-asterix"))
        routes.answer(searchPath(bdId(RecordedOn.Latest)), fixtureBody("search-asterix"))

        val found = found("astérix")

        assertThat(found.books.items).hasSize(1)
        assertThat(found.series.items).hasSize(1)
    }

    @Test
    fun `a blank text finds nothing, and asks nothing`() = runBlocking<Unit> {
        val found = found("  ")

        assertThat(found.isEmpty).isTrue()
        assertThat(routes.requests()).isEmpty()
    }

    @Test
    fun `a library the account may not search is left out`() = runBlocking<Unit> {
        routes.answer(searchPath(livresId(RecordedOn.Latest)), fixtureBody("search-herbert"))
        routes.fail(searchPath(bdId(RecordedOn.Latest)), 403)

        val found = found("herbert")

        assertThat(found.authors.items.map { it.name }).containsExactly("Frank Herbert")
    }

    @Test
    fun `a library whose search fails otherwise fails the search`() = runBlocking<Unit> {
        routes.answer(searchPath(livresId(RecordedOn.Latest)), fixtureBody("search-herbert"))
        routes.fail(searchPath(bdId(RecordedOn.Latest)), 500)

        assertThat(search.search("herbert")).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `2_26_3's search is read, its whole items telling their formats by their files`() = runBlocking<Unit> {
        answer(RecordedOn.Minimum)
        routes.answer(searchPath(livresId(RecordedOn.Minimum)), fixtureBody("search-potter", RecordedOn.Minimum))
        routes.answer(searchPath(bdId(RecordedOn.Minimum)), fixtureBody("search-asterix", RecordedOn.Minimum))

        val found = found("potter")

        val books = found.books.items.associateBy { it.title }
        assertThat(books.getValue("Harry Potter et les Reliques de la Mort").media)
            .isEqualTo(MediaProfile.TextAndAudio)
        assertThat(books.getValue("Harry Potter").media).isEqualTo(MediaProfile.TextOnly)
        assertThat(books.getValue("Astérix 1").pagedKind).isEqualTo(PagedKind.Comic)
        assertThat(found.series.items.map { it.bookCount }).containsExactly(9, 8, 4, 1).inOrder()
    }

    @Test
    fun `2_26_3's search finds authors as 2_38_0's does`() = runBlocking<Unit> {
        answer(RecordedOn.Minimum)
        routes.answer(searchPath(livresId(RecordedOn.Minimum)), fixtureBody("search-herbert", RecordedOn.Minimum))

        val herbert = found("herbert").authors.items.single()

        assertThat(herbert.name).isEqualTo("Frank Herbert")
        assertThat(herbert.bookCount).isEqualTo(17)
    }

    private fun searchPath(libraryId: String) = "/api/libraries/$libraryId/search"
}
