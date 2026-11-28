package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.Livres
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A library's authors, asked whole once and sorted, narrowed and paged on the device; an author's page and books,
 * from one answer of the author with the author's items and series.
 */
class AbsAuthorsTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var rig: BrowseRig

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes)
        routes.answer(AuthorsPath, fixtureBody("authors-livres"))
        routes.answer(AdamsPath, fixtureBody("author-adams"))
        rig = BrowseRig(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun authorsOf() = AbsAuthors(rig.profileServer, rig.progress, rig.whole, pageSize = 3)

    private suspend fun names(sort: AuthorSort, direction: SortDirection, text: String? = null): List<String> =
        authorsOf().authors(sort, direction, text, Livres).items.asSnapshot { appendScrollWhile { true } }
            .map(Author::name)

    @Test
    fun `the authors are asked once and sorted by their last names, as a reader sorts them`() = runBlocking<Unit> {
        val authors = authorsOf().authors(AuthorSort.Name, SortDirection.Ascending, text = null, library = Livres)

        val all = authors.items.asSnapshot { appendScrollWhile { true } }

        assertThat(all.map { it.name }).containsExactly(
            "Douglas Adams",
            "Antoine de Saint-Exupéry",
            "Frank Herbert",
            "Stieg Larsson",
            "Edgar Allan Poe",
            "Kim Stanley Robinson",
            "J.K. Rowling",
            "Jules Verne",
            "Andy Weir",
            "Martha Wells",
        ).inOrder()
        assertThat(all.first()).isEqualTo(Author(AdamsId.asAuthorId(), "Douglas Adams", 11, portraitUrl = null))
        assertThat(authors.total.value).isEqualTo(10)
        assertThat(routes.requests(AuthorsPath)).hasSize(1)
    }

    @Test
    fun `by book count, the names break the ties whichever way the counts go`() = runBlocking<Unit> {
        assertThat(names(AuthorSort.BookCount, SortDirection.Descending).take(5)).containsExactly(
            "Jules Verne",
            "Edgar Allan Poe",
            "Frank Herbert",
            "J.K. Rowling",
            "Douglas Adams",
        ).inOrder()
        assertThat(names(AuthorSort.Name, SortDirection.Descending).first()).isEqualTo("Martha Wells")
    }

    @Test
    fun `a text narrows the authors on the device, case and accents ignored`() = runBlocking<Unit> {
        assertThat(names(AuthorSort.Name, SortDirection.Ascending, " EXUPERY")).containsExactly(
            "Antoine de Saint-Exupéry",
        )
    }

    @Test
    fun `an author's page has the author's name, biography and number of books`() = runBlocking<Unit> {
        val described = recordedObject("author-adams").with("description", JsonPrimitive("Wrote about towels."))
        routes.answer(AdamsPath, described.toString())

        val author = authorsOf().author(AdamsId.asAuthorId())

        assertThat(author).isEqualTo(
            ApiResult.Success(
                AuthorDetail(Author(AdamsId.asAuthorId(), "Douglas Adams", 11, null), "Wrote about towels."),
            ),
        )
        val asked = routes.requests(AdamsPath).single().url
        assertThat(asked.queryParameter("include")).isEqualTo("items,series")
    }

    @Test
    fun `an author's books are ordered by series and place, then by title, each with its series`() {
        RecordedOn.entries.forEach { on ->
            val books = authorBooks(on)

            assertThat(books.map { it.title }).containsExactly(
                "Le Guide du voyageur galactique",
                "Le dernier restaurant avant la fin du monde",
                "La vie, l'univers et le reste",
                "Salut, et encore merci pour le poisson",
                "Globalement inoffensive",
                "The Ultimate Hitchhiker’s Guide to the Galaxy",
                "The Hitchhiker's Guide to the Galaxy",
                "The Restaurant at the End of the Universe",
                "Life, the Universe and Everything",
                "So Long, and Thanks For All the Fish",
                "Mostly Harmless",
                "Le Petit Prince",
            ).inOrder()
            val first = books.first().series
            assertThat(first?.name).isEqualTo("H2G2")
            assertThat(first?.id).isNotNull()
            assertThat(first?.index).isEqualTo("1")
            assertThat(books.last().series).isNull()
            assertThat(books.first { it.title == "Life, the Universe and Everything" }.readState)
                .isEqualTo(ReadState.Read)
        }
    }

    /**
     * Douglas Adams's books as recorded on [on], with a book of no series added, which the author's series do not
     * name.
     */
    private fun authorBooks(on: RecordedOn): List<BookSummary> = runBlocking {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        val adams = recordedObject("author-adams", on)
        val prince = recordedObject("items-livres-all", on).getValue("results").jsonArray
            .first { it.jsonObject.title == "Le Petit Prince" }
            .let { (it as JsonObject).with("media", it.getValue("media").jsonObject.withoutSeries()) }
        val items = adams.getValue("libraryItems").jsonArray
        val id = (adams.getValue("id") as JsonPrimitive).content
        routes.answer("/api/authors/$id", adams.with("libraryItems", JsonArray(listOf(prince) + items)).toString())
        rig = BrowseRig(server)
        (authorsOf().authorBooks(id.asAuthorId()) as ApiResult.Success).value
    }

    @Test
    fun `an author the server does not know is not found`() = runBlocking<Unit> {
        assertThat(authorsOf().author("nobody".asAuthorId())).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(authorsOf().authorBooks("nobody".asAuthorId())).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(authorsOf().authorBooks("a/b".asAuthorId())).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    private companion object {
        const val AdamsId = "12e39226-c963-4028-9146-db276d8a0f05"
        const val AdamsPath = "/api/authors/$AdamsId"
        const val AuthorsPath = "/api/libraries/$LivresId/authors"
    }
}

/** The title of a recorded item. */
private val JsonObject.title: String
    get() = (getValue("media").jsonObject.getValue("metadata").jsonObject.getValue("title") as JsonPrimitive).content

/** An item's media whose metadata names no series. */
private fun JsonObject.withoutSeries(): JsonObject {
    val metadata = getValue("metadata").jsonObject.with("seriesName", JsonPrimitive(""))
    return with("metadata", metadata)
}
