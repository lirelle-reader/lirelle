package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.Livres
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedLibrary
import app.tfdn.lirelle.source.audiobookshelf.testing.bookListOn
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.libraryPath
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedItems
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.title
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import java.text.Collator
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
 * A genre's books: the server's genre filter (`genres.<base64 name>`), paged by the server, for the library asked,
 * or, without one, for each book library one after the other.
 */
class AbsGenreBooksTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var list: AbsBookList

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedLibrary(routes)
        routes.answer(itemsPath(LivresId), pagedItems("items-genre-humour"))
        list = bookListOn(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a genre's books are the server's, filtered by the genre's name in base64, page by page`() = runBlocking<Unit> {
        val request = BookListRequest(BookSort.Title, SortDirection.Ascending, genre = "Humour", library = Livres)
        val books = list.books(request)

        val all = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(all).hasSize(61)
        assertThat(books.total.value).isEqualTo(61)
        val pages = routes.requests(itemsPath(LivresId))
        assertThat(pages).hasSize(13)
        pages.forEachIndexed { index, page ->
            assertThat(page.url.encodedQuery).contains("filter=genres.SHVtb3Vy")
            assertThat(page.url.queryParameter("page")).isEqualTo("$index")
            assertThat(page.url.queryParameter("limit")).isEqualTo("5")
            assertThat(page.url.queryParameter("sort")).isEqualTo("media.metadata.title")
            assertThat(page.url.queryParameter("desc")).isEqualTo("0")
        }
    }

    @Test
    fun `a name whose base64 has a plus, a slash and an equals sign is sent escaped`() = runBlocking<Unit> {
        // "Poésie?~?" is UG/DqXNpZT9+Pw== in base64: the server would read a plus left as it is as a space.
        list.bookPage(BookListRequest(genre = "Poésie?~?", library = Livres), page = 0, size = 5)

        val query = routes.requests(itemsPath(LivresId)).single().url.encodedQuery
        assertThat(query).contains("filter=genres.UG%2FDqXNpZT9%2BPw%3D%3D")
    }

    @Test
    fun `without a library a genre's books are each book library's, one after the other`() = runBlocking<Unit> {
        val books = list.books(BookListRequest(genre = "Humour"))

        val all = books.items.asSnapshot { appendScrollWhile { true } }

        assertThat(all).hasSize(66)
        assertThat(all.take(61).map { it.id.value }.toSet()).hasSize(61)
        assertThat(all.drop(61).map { it.title }).containsExactly(
            "Aldebaran 1",
            "Alix 1",
            "Alix Senator 1",
            "Amazonie 1",
            "Antarès 1",
        ).inOrder()
        assertThat(books.total.value).isEqualTo(66)
        val bd = routes.requests(itemsPath(BdId))
        assertThat(bd.first().url.encodedQuery).contains("filter=genres.SHVtb3Vy")
        val firstBd = routes.requests().indexOf(bd.first())
        assertThat(routes.requests(itemsPath(LivresId)).all { routes.requests().indexOf(it) < firstBd }).isTrue()
    }

    @Test
    fun `a page of a genre across the libraries follows the same order`() = runBlocking<Unit> {
        val page = list.bookPage(BookListRequest(genre = "Humour"), page = 12, size = 5) as ApiResult.Success

        assertThat(page.value.total).isEqualTo(66)
        assertThat(page.value.items.map { it.title }).containsExactly(
            "Un amour de monte-en-l'air",
            "Aldebaran 1",
            "Alix 1",
            "Alix Senator 1",
            "Amazonie 1",
        ).inOrder()
    }

    @Test
    fun `a genre the library writes with a stray space is asked of the server by the library's own name`() =
        runBlocking<Unit> {
            routes.answer(libraryPath(LivresId), """{"filterdata": {"genres": ["Humour ", "Essai"]}}""")

            list.bookPage(BookListRequest(genre = "Humour", library = Livres), page = 0, size = 5)

            // "Humour " is SHVtb3VyIA== in base64.
            val query = routes.requests(itemsPath(LivresId)).single().url.encodedQuery
            assertThat(query).contains("filter=genres.SHVtb3VyIA%3D%3D")
            assertThat(routes.requests(libraryPath(LivresId)).single().url.queryParameter("include"))
                .isEqualTo("filterdata")
        }

    @Test
    fun `a genre the library writes several ways narrows its whole list on the device`() = runBlocking<Unit> {
        routes.answer(libraryPath(LivresId), """{"filterdata": {"genres": ["Humour", "Humour ", "Essai"]}}""")
        val all = recordedObject("items-livres-all").getValue("results").jsonArray.map { it.jsonObject }
        val spaced = all.first { it.title == "Project Hail Mary" }.withGenres("Humour ")
        val items = all.map { if (it.title == "Project Hail Mary") spaced else it }
        val answer = JsonObject(mapOf("results" to JsonArray(items), "total" to JsonPrimitive(items.size)))
        routes.answer(itemsPath(LivresId), answer.toString())
        val request = BookListRequest(BookSort.Title, SortDirection.Ascending, genre = "Humour", library = Livres)

        val books = list.books(request).items.asSnapshot { appendScrollWhile { true } }

        val humour = items.filter { item -> item.genres.any { it.trim() == "Humour" } }.map { it.title }
        assertThat(books.map { it.title }).containsExactlyElementsIn(humour)
        assertThat(books.map { it.title }).contains("Project Hail Mary")
        assertThat(books.map { it.title }).isInOrder(readerOrder())
        val asked = routes.requests(itemsPath(LivresId)).map { it.url }
        assertThat(asked.map { it.queryParameter("limit") }.distinct()).containsExactly("0")
        assertThat(asked.mapNotNull { it.queryParameter("filter") }).isEmpty()
    }

    @Test
    fun `a genre with a filter narrows the library's whole list on the device`() = runBlocking<Unit> {
        val request = BookListRequest(
            BookSort.Title,
            SortDirection.Ascending,
            BookFilters(formats = setOf(FormatFilter.Audio)),
            genre = "Humour",
            library = Livres,
        )
        routes.answer(itemsPath(LivresId), pagedItems("items-livres-all"))

        val books = list.books(request).items.asSnapshot { appendScrollWhile { true } }

        assertThat(books.map { it.title }).containsExactly(
            "And Another Thing…",
            "Life, the Universe and Everything",
            "Mostly Harmless",
            "So Long, and Thanks For All the Fish",
            "The Hitchhiker's Guide to the Galaxy",
            "The Restaurant at the End of the Universe",
        ).inOrder()
        val whole = routes.requests(itemsPath(LivresId)).single()
        assertThat(whole.url.queryParameter("limit")).isEqualTo("0")
        assertThat(whole.url.queryParameter("filter")).isNull()
    }
}

/** The genres of a recorded item. */
private val JsonObject.genres: List<String>
    get() = getValue("media").jsonObject.getValue("metadata").jsonObject["genres"]?.jsonArray
        ?.map { (it as JsonPrimitive).content }.orEmpty()

/** A recorded item of the genres [names] alone. */
private fun JsonObject.withGenres(vararg names: String): JsonObject {
    val media = getValue("media").jsonObject
    val metadata = media.getValue("metadata").jsonObject
    return with("media", media.with("metadata", metadata.with("genres", JsonArray(names.map(::JsonPrimitive)))))
}

/** Titles as a reader sorts them, case and accents aside. */
private fun readerOrder(): Comparator<String> = Collator.getInstance().apply { strength = Collator.PRIMARY }
    .let { collator -> Comparator { a, b -> collator.compare(a, b) } }
