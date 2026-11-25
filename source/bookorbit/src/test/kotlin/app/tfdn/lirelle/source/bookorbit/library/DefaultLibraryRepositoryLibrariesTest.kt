package app.tfdn.lirelle.source.bookorbit.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.AccountApi
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.ServerFeatures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val LibraryRule = """{"type":"rule","field":"library","operator":"includesAny","value":["EBooks"]}"""
private const val AnyLibraryRule = """"field":"library""""

private const val Cards = """{"items":[{"id":1,"title":"B1"}],"total":1}"""
private const val SeriesPage = """{"items":[{"id":1,"name":"S1","bookCount":2,"readCount":1}],"total":1}"""
private const val AuthorsPage = """{"items":[],"total":0}"""

/** The server's libraries, and the lists narrowed to one of them: books by its name, series and authors by its id. */
class DefaultLibraryRepositoryLibrariesTest {

    private val server = MockWebServer()
    private lateinit var repository: DefaultLibraryRepository

    private val ebooks = ServerLibrary(LibraryId("3"), "EBooks")

    @Before
    fun setUp() {
        server.start()
        repository = DefaultLibraryRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, LibraryApi::class.java),
            errors = ErrorMapper(ApiJson),
            features = ServerFeatures(
                api = ApiProvider(OkHttpClient(), ApiJson, AccountApi::class.java),
                errors = ErrorMapper(ApiJson),
                serverUrl = { server.serverUrl },
            ),
            serverUrl = { server.serverUrl },
            audio = DefaultAudioRepository(
                api = ApiProvider(OkHttpClient(), ApiJson, AudioApi::class.java),
                errors = ErrorMapper(ApiJson),
                serverUrl = { server.serverUrl },
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** The body of the books query [request] makes for [source]. */
    private suspend fun bodyOf(source: BookListSource, request: BookListRequest): String {
        server.enqueueJson(Cards)
        repository.books(source, request).items.asSnapshot()
        return server.takeRequest().bodyText()
    }

    // --- libraries

    @Test
    fun `the libraries are read in the server's order`() = runBlocking {
        // A real answer's order and a few of its many keys.
        server.enqueueJson(
            """[{"id":8,"name":"BDs Tous-Publics","icon":"book-open","displayOrder":0,"folders":[{"id":11}]},
                {"id":3,"name":"EBooks","icon":"library","displayOrder":1,"folders":[]},
                {"id":9,"name":"Shonen","icon":null,"displayOrder":2}]""",
        )

        val result = repository.libraries() as ApiResult.Success

        assertThat(result.value).containsExactly(
            ServerLibrary(LibraryId("8"), "BDs Tous-Publics"),
            ServerLibrary(LibraryId("3"), "EBooks"),
            ServerLibrary(LibraryId("9"), "Shonen"),
        ).inOrder()
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/libraries")
    }

    @Test
    fun `a library without a name is left out`() = runBlocking<Unit> {
        server.enqueueJson("""[{"id":8},{"id":3,"name":"EBooks"},{"id":9,"name":"  "}]""")

        val result = repository.libraries() as ApiResult.Success

        assertThat(result.value).containsExactly(ebooks)
    }

    // --- books

    @Test
    fun `the books of one library are asked by its name`() = runBlocking {
        server.enqueueJson(Cards)

        repository.books(BookListSource.AllBooks, BookListRequest(library = ebooks)).items.asSnapshot()

        val request = server.takeRequest()
        assertThat(request.target()).isEqualTo("/api/v1/books/query")
        assertThat(request.bodyText()).contains(LibraryRule)
    }

    @Test
    fun `the books of every library carry no library rule`() = runBlocking {
        val body = bodyOf(BookListSource.AllBooks, BookListRequest())

        assertThat(body).doesNotContain(AnyLibraryRule)
        assertThat(body).doesNotContain(""""filter"""")
    }

    @Test
    fun `the books of a collection and of a smart list are never narrowed to a library`() = runBlocking {
        val request = BookListRequest(library = ebooks)

        val collection = bodyOf(BookListSource.Collection(ShelfId("7")), request)
        val smartList = bodyOf(BookListSource.SmartList(ShelfId("9")), request)

        assertThat(collection).doesNotContain(AnyLibraryRule)
        assertThat(smartList).doesNotContain(AnyLibraryRule)
    }

    @Test
    fun `a library and the other filters go together`() = runBlocking {
        val request = BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Pdf)), library = ebooks)

        val body = bodyOf(BookListSource.AllBooks, request)

        assertThat(body).contains(
            """"filter":{"type":"group","join":"AND","rules":[""" +
                """{"type":"rule","field":"format","operator":"includesAny","value":["pdf"]},$LibraryRule]}""",
        )
    }

    // --- series

    @Test
    fun `the series of one library are asked by its id`() = runBlocking {
        server.enqueueJson(SeriesPage)
        server.enqueueJson(SeriesPage)

        repository.series(library = ebooks)
        repository.series()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series?page=0&size=100&libraryId=3")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series?page=0&size=100")
    }

    // --- authors

    @Test
    fun `the authors of one library are asked by its id`() = runBlocking {
        server.enqueueJson(AuthorsPage)
        server.enqueueJson(AuthorsPage)

        repository.authors(AuthorSort.Name, SortDirection.Ascending, library = ebooks).items.asSnapshot()
        repository.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot()

        assertThat(server.takeRequest().target()).endsWith("&libraryId=3")
        assertThat(server.takeRequest().target()).doesNotContain("libraryId")
    }
}
