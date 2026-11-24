package app.tfdn.lirelle.source.komga.downloads

import app.tfdn.lirelle.core.downloads.engine.Downloader
import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.komga.comics.KomgaPageSource
import app.tfdn.lirelle.source.komga.comics.KomgaPagesApi
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val Book = BookId("0BK1A")

/** A comic of Komga is one file, whose id is the book's. */
private val Album = FileId("0BK1A")

/** Komga's refusal of a page to an account without `PAGE_STREAMING`, written by hand. */
private const val ForbiddenJson = """{"timestamp":"2026-11-23T10:00:00Z","status":403,"error":"Forbidden",""" +
    """"path":"/api/v1/books/0BK1A/pages/1"}"""

/**
 * A Komga server's refusals of a kept page, read from their status as its other refusals to the profile's key; a
 * page refused (403) is the account's rights: a download needs `PAGE_STREAMING`, which the account lacks.
 */
class KomgaDownloadRefusalsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val refusals = KomgaDownloadRefusals()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `a page refused during a download fails as not allowed`(): Unit = runBlocking {
        val pages = KomgaPageSource(
            KomgaApis(OkHttpClient(), KomgaJson, KomgaPagesApi::class.java),
            KomgaErrors(),
        ) { server.serverUrl }
        val files = DownloadFiles(folder.newFolder("downloads"))
        val downloader = Downloader(OkHttpClient(), files, KomgaDownloadLocations(pages), refusals, Dispatchers.IO)
        val page = PlannedFile(
            name = "0",
            source = FileSource.Page(Album, PagedKind.Comic, 0),
            version = "v1",
            expectedBytes = null,
            resumable = false,
            kind = FileKind.Page,
        )
        server.enqueueJson(ForbiddenJson, code = 403)

        val result = downloader.fetch(Book, DownloadFormat.Text, page) {}

        assertThat(result).isEqualTo(FetchResult.Refused(DownloadFailure.NotAllowed))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/v1/books/0BK1A/pages/1")
        assertThat(files.file(Book, DownloadFormat.Text, "0").exists()).isFalse()
    }

    @Test
    fun `a page not found during a download, Komga analysing the album again, fails as one to retry, not as gone`() {
        assertThat(refusals.failure(response(404))).isEqualTo(DownloadFailure.Server(ApiError.Unexpected))
    }

    @Test
    fun `any other refusal of a file is read from its status`() {
        assertThat(refusals.failure(response(503))).isEqualTo(DownloadFailure.Server(ApiError.ServerFailed))
        assertThat(refusals.failure(response(429))).isEqualTo(DownloadFailure.Server(ApiError.TooManyAttempts))
        assertThat(refusals.failure(response(401))).isEqualTo(DownloadFailure.Server(ApiError.SessionExpired))
        assertThat(refusals.failure(response(403))).isEqualTo(DownloadFailure.NotAllowed)
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://bd.example.com/api/v1/books/1/pages/1").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body("{}".toResponseBody())
        .build()
}
