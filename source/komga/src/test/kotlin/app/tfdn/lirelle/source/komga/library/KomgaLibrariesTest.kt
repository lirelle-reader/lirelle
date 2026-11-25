package app.tfdn.lirelle.source.komga.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadingBookJson
import app.tfdn.lirelle.source.komga.testing.SeriesJson
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pageJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The libraries of a Komga server (`GET api/v1/libraries`, a bare array), and the lists narrowed to one of them:
 * the books of the whole library and the series carry the library's id in their search, a read list's books never.
 */
class KomgaLibrariesTest {

    private val server = MockWebServer()
    private lateinit var library: KomgaLibrary

    private val seinen = ServerLibrary(LibraryId("0DSQ0FDV47DMP"), "Seinen")
    private val inSeinen = """{"libraryId":{"operator":"is","value":"0DSQ0FDV47DMP"}}"""
    private val notDeleted = """{"deleted":{"operator":"isFalse"}}"""

    @Before
    fun setUp() {
        server.start()
        library = KomgaLibrary(
            FakeProfileSession(komgaSession(server.serverUrl)),
            KomgaApis(OkHttpClient(), KomgaJson, KomgaLibraryApi::class.java),
            KomgaErrors(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun item(id: String, name: String) =
        """{"id":"$id","name":"$name","root":false,"importLastModified":"2026-11-01T10:00:00Z",""" +
            """"lastModified":"2026-11-02T10:00:00Z","unavailable":false}"""

    @Test
    fun `the libraries are read in the server's order, with what the app does not read ignored`() = runBlocking<Unit> {
        server.enqueueJson(
            "[" + item("0DSQ0FDV47DMP", "Seinen") + "," + item("0DSQ0FDV5Z6QG", "Shōnen") + "," +
                item("0DSQ0FDV7ABCD", "Comics") + "]",
        )

        val libraries = (library.libraries() as ApiResult.Success).value

        assertThat(libraries).containsExactly(
            ServerLibrary(LibraryId("0DSQ0FDV47DMP"), "Seinen"),
            ServerLibrary(LibraryId("0DSQ0FDV5Z6QG"), "Shōnen"),
            ServerLibrary(LibraryId("0DSQ0FDV7ABCD"), "Comics"),
        ).inOrder()
        val asked = server.takeRequest()
        assertThat("${asked.method} ${asked.url.encodedPath}").isEqualTo("GET /api/v1/libraries")
    }

    @Test
    fun `a library without a name is left out`() = runBlocking<Unit> {
        server.enqueueJson(
            "[" + item("0DSQ0FDV47DMP", "Seinen") + "," + item("0DSQ0FDV5Z6QG", "  ") + "," +
                """{"id":"0DSQ0FDV7ABCD"}]""",
        )

        val libraries = (library.libraries() as ApiResult.Success).value

        assertThat(libraries).containsExactly(seinen)
    }

    @Test
    fun `the books of the whole library of one library are asked with its id`() = runBlocking<Unit> {
        server.enqueueJson(pageJson(ReadingBookJson))

        library.books(BookListSource.AllBooks, BookListRequest(library = seinen)).items.asSnapshot()

        val asked = server.takeRequest()
        assertThat(Json.parseToJsonElement(asked.bodyText()))
            .isEqualTo(Json.parseToJsonElement("""{"condition":{"allOf":[$notDeleted,$inSeinen]}}"""))
    }

    @Test
    fun `the series of a genre are asked in the chosen library only, as its books are`() = runBlocking<Unit> {
        server.enqueueJson(pageJson(SeriesJson))
        server.enqueueJson(pageJson(ReadingBookJson))
        val request = BookListRequest(genre = "Drama", library = seinen)

        library.books(BookListSource.AllBooks, request).items.asSnapshot()

        assertThat(Json.parseToJsonElement(server.takeRequest().bodyText()))
            .isEqualTo(seriesSearch(text = null, genre = "Drama", library = seinen))
        assertThat(server.takeRequest().bodyText()).contains(inSeinen)
    }

    @Test
    fun `a read list's books are not narrowed to the library of the request`() = runBlocking<Unit> {
        server.enqueueJson(pageJson(ReadingBookJson))

        library.books(BookListSource.Collection(ShelfId("0RL1")), BookListRequest(library = seinen)).items.asSnapshot()

        assertThat(server.takeRequest().bodyText()).doesNotContain("libraryId")
    }

    @Test
    fun `the series of one library are asked with its id`() = runBlocking<Unit> {
        server.enqueueJson(pageJson(SeriesJson))

        library.series(text = "tide", library = seinen)

        assertThat(Json.parseToJsonElement(server.takeRequest().bodyText())).isEqualTo(
            Json.parseToJsonElement(
                """{"condition":{"allOf":[$notDeleted,$inSeinen]},"fullTextSearch":"tide"}""",
            ),
        )
    }

    @Test
    fun `the series of every library carry no library`() = runBlocking<Unit> {
        server.enqueueJson(pageJson(SeriesJson))

        library.series(text = null, library = null)

        assertThat(server.takeRequest().bodyText()).doesNotContain("libraryId")
    }
}
