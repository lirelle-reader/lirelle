package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueText
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.libraryServer
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Two book libraries out of their order, and a podcast library between them. */
private const val Unordered = """{"libraries":[
    {"id":"lib-b","name":"BD","displayOrder":3,"mediaType":"book"},
    {"id":"lib-p","name":"Podcasts","displayOrder":1,"mediaType":"podcast"},
    {"id":"lib-a","name":"Livres","displayOrder":2,"mediaType":"book"}]}"""

/**
 * The libraries are the account's book libraries, in the server's order, podcasts left out; a list given no library
 * shows the first. The server's refusals are told as the screens show them: what the account may not see is not
 * found, a token refused is a session that ended.
 */
class AbsLibrariesTest {

    private val server = MockWebServer()
    private lateinit var remote: AbsProfileServer

    @Before
    fun setUp() {
        server.start()
        remote = libraryServer(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the libraries are the book libraries, in the server's order, on both versions`() = runBlocking {
        RecordedOn.entries.forEach { on ->
            server.enqueueJson(fixtureBody("libraries", on))

            val libraries = (remote.libraries() as ApiResult.Success).value

            assertWithMessage(on.name).that(libraries.map { it.name }).containsExactly("Livres", "BD").inOrder()
            assertWithMessage(on.name).that(libraries.first().id.value).hasLength(36)
            val request = server.takeRequest()
            assertWithMessage(on.name).that(request.pathText()).isEqualTo("/api/libraries")
            assertWithMessage(on.name).that(request.headers["Authorization"]).isEqualTo("Bearer access-1")
        }
    }

    @Test
    fun `the libraries are ordered as the server orders them, whatever order they come in`() = runBlocking {
        server.enqueueJson(Unordered)

        assertThat(remote.libraries()).isEqualTo(
            ApiResult.Success(
                listOf(ServerLibrary(LibraryId("lib-a"), "Livres"), ServerLibrary(LibraryId("lib-b"), "BD")),
            ),
        )
    }

    @Test
    fun `a list given no library shows the first book library`() = runBlocking {
        server.enqueueJson(Unordered)

        val first = remote.call { it.libraryOrFirst(null) }

        assertThat(first).isEqualTo(ApiResult.Success(ServerLibrary(LibraryId("lib-a"), "Livres")))
    }

    @Test
    fun `a list given a library shows it, and the server is not asked`() = runBlocking {
        val chosen = ServerLibrary(LibraryId("lib-b"), "BD")

        assertThat(remote.call { it.libraryOrFirst(chosen) }).isEqualTo(ApiResult.Success(chosen))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a server with no book library gives no library to show`() = runBlocking {
        server.enqueueJson("""{"libraries":[{"id":"lib-p","name":"Podcasts","mediaType":"podcast"}]}""")

        assertThat(remote.call { it.libraryOrFirst(null) }).isEqualTo(ApiResult.Success(null))
    }

    @Test
    fun `what the account may not see is not found`() = runBlocking {
        server.enqueueText("Forbidden", code = 403)
        server.enqueueText("Not found", code = 404)

        assertThat(remote.call { it.api.item("hidden") }).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(remote.call { it.api.item("gone") }).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/items/hidden")
    }

    @Test
    fun `a token refused is a session that ended, a failing server a failure`() = runBlocking {
        server.enqueueRefusal()
        server.enqueueText("Internal error", code = 500)

        assertThat(remote.libraries()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(remote.libraries()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `a whole item is asked expanded`() = runBlocking {
        server.enqueueJson(fixtureBody("item-h2g2-expanded"))

        val item = remote.call { it.api.item("4c18e748-4cde-4876-8cac-4d20a40e0da7") }

        assertThat((item as ApiResult.Success).value.media.metadata.title)
            .isEqualTo("The Hitchhiker's Guide to the Galaxy")
        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/items/4c18e748-4cde-4876-8cac-4d20a40e0da7")
        assertThat(request.url.queryParameter("expanded")).isEqualTo("1")
    }

    @Test
    fun `the progress is read from the account`() = runBlocking {
        server.enqueueJson(fixtureBody("me"))

        val progress = remote.call { it.api.me().mediaProgress }

        assertThat((progress as ApiResult.Success).value).hasSize(8)
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/me")
    }
}
