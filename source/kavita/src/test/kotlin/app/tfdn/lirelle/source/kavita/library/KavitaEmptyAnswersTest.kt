package app.tfdn.lirelle.source.kavita.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.comics.NoKavitaPages
import app.tfdn.lirelle.source.kavita.downloads.KavitaDownloadRefusals
import app.tfdn.lirelle.source.kavita.downloads.NoKavitaDownloadLocations
import app.tfdn.lirelle.source.kavita.history.EmptyKavitaHistory
import app.tfdn.lirelle.source.kavita.network.KavitaAccountPictures
import app.tfdn.lirelle.source.kavita.network.PassThrough
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

private val Book = BookId("12")
private val File = FileId("34")
private val NotFound = ApiResult.Failure(ApiError.NotFound)
private val Nothing = ApiResult.Success(emptyList<Any>())

/**
 * Until the library is browsed (#50) and read (#51), a Kavita profile's server has nothing to show: every list is
 * empty, everything asked for by its id is not found, every write is refused, nothing is served — and nothing is
 * asked of the server.
 */
class KavitaEmptyAnswersTest {

    @Test
    fun `the library is empty, and what is asked by its id is not found`() = runBlocking {
        val library = EmptyKavitaLibrary()

        assertThat(library.libraries()).isEqualTo(Nothing)
        assertThat(library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()).isEmpty()
        assertThat(library.books(BookListSource.AllBooks, BookListRequest()).total.value).isEqualTo(0)
        assertThat((library.bookPage(BookListRequest(), page = 0, size = 60) as ApiResult.Success).value.items)
            .isEmpty()
        assertThat(library.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot()).isEmpty()
        assertThat(library.series()).isEqualTo(Nothing)
        assertThat(library.genres()).isEqualTo(Nothing)
        assertThat((library.shelves() as ApiResult.Success).value.isEmpty).isTrue()
        assertThat((library.search("dune") as ApiResult.Success).value.isEmpty).isTrue()
        assertThat((library.homeRow(HomeRowKind.Continue) as ApiResult.Success).value.books).isEmpty()
        assertThat(library.book(Book)).isEqualTo(NotFound)
        assertThat(library.bookReading(Book)).isEqualTo(NotFound)
        assertThat(library.seriesBooks(SeriesId("5"))).isEqualTo(NotFound)
    }

    @Test
    fun `no file is read, no place is kept and none is saved`() = runBlocking {
        val reader = EmptyKavitaReader()

        assertThat(reader.fileUrl(File, audioless = false)).isNull()
        assertThat(reader.position(File)).isEqualTo(NotFound)
        assertThat(reader.savePosition(File, cfi = null, percent = 50.0, page = 3)).isEqualTo(NotFound)
        assertThat(reader.finishedReading(Book)).isEqualTo(ApiResult.Success(null))
        assertThat(reader.nextVolume(SeriesId("5"), Book)).isEqualTo(ApiResult.Success(null))
        assertThat(reader.download(File, audioless = false, ByteArrayOutputStream()) {}).isEqualTo(NotFound)
    }

    @Test
    fun `no page, no audio and no marks are served`() = runBlocking {
        assertThat(EmptyKavitaAudio().assetUrl(Book, "a")).isNull()
        assertThat(EmptyKavitaAudio().manifest(Book)).isEqualTo(NotFound)
        assertThat(EmptyKavitaAudioBookmarks().list(Book)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().bookmarks(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().highlights(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().addBookmark(Book, File, "cfi", "title")).isEqualTo(NotFound)
        assertThat(EmptyKavitaReadAlong().trackUrl(Book, File, "a.mp3")).isNull()
        assertThat(NoKavitaPages().servesByPages(PagedKind.Comic)).isFalse()
        assertThat(NoKavitaPages().pageUrl(PagedKind.Comic, File, 0)).isNull()
        assertThat(NoKavitaPages().pageCount(PagedKind.Comic, File)).isEqualTo(NotFound)
        assertThat(NoKavitaPages().page(PagedKind.Comic, File, 0, PageSink { ByteArrayOutputStream() }))
            .isEqualTo(NotFound)
        assertThat(NoKavitaDownloadLocations().url(Book, FileSource.Whole(File))).isNull()
        assertThat(KavitaAccountPictures.pictureUrl("https://kavita.example.com", "/avatar")).isNull()
    }

    @Test
    fun `the history is an empty list, with nothing more to show`() = runBlocking {
        val history = EmptyKavitaHistory()

        assertThat(history.capabilities).isEqualTo(HistoryCapabilities(false, false, false, false, false, false, false))
        val page = (history.log(HistoryPeriod(null), emptySet(), true, offset = 0, limit = 20) as ApiResult.Success)
        assertThat(page.value.records).isEmpty()
        assertThat(page.value.total).isEqualTo(0)
        assertThat(history.marks(ReadId("9"))).isEqualTo(NotFound)
        assertThat(history.setRating(ReadId("9"), stars = 4)).isEqualTo(NotFound)
    }

    @Test
    fun `a refusal of a file is read from its status`() {
        val refusals = KavitaDownloadRefusals()

        assertThat(refusals.refusal(response(404))).isEqualTo(ApiError.NotFound)
        assertThat(refusals.refusal(response(503))).isEqualTo(ApiError.ServerFailed)
        assertThat(refusals.refusal(response(429))).isEqualTo(ApiError.TooManyAttempts)
        assertThat(refusals.refusal(response(401))).isEqualTo(ApiError.SessionExpired)
        assertThat(refusals.refusal(response(403))).isEqualTo(ApiError.Unexpected)
    }

    @Test
    fun `no request is a long transfer, each goes on as it is`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 200, body = "ok"))
            val client = OkHttpClient.Builder().addInterceptor(PassThrough).build()

            val answer = client.newCall(Request.Builder().url(server.url("/api/Download/chapter")).build()).execute()

            assertThat(answer.body.string()).isEqualTo("ok")
        } finally {
            server.close()
        }
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://kavita.example.com/api/Download/chapter").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body("".toResponseBody())
        .build()
}
