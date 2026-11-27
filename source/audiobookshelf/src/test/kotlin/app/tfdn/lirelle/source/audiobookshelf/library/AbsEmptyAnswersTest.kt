package app.tfdn.lirelle.source.audiobookshelf.library

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
import app.tfdn.lirelle.source.audiobookshelf.comics.NoAbsPages
import app.tfdn.lirelle.source.audiobookshelf.downloads.AbsDownloadRefusals
import app.tfdn.lirelle.source.audiobookshelf.downloads.NoAbsDownloadLocations
import app.tfdn.lirelle.source.audiobookshelf.history.EmptyAbsHistory
import app.tfdn.lirelle.source.audiobookshelf.network.AbsPassThrough
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

/** Audiobookshelf's ids are UUIDs: the tests' look like them. */
private val Book = BookId("li_8f2a")
private val File = FileId("ino_4c1d")
private val NotFound = ApiResult.Failure(ApiError.NotFound)
private val Nothing = ApiResult.Success(emptyList<Any>())

/**
 * Until the library is browsed (#67), listened to (#68) and read (#69), an Audiobookshelf profile's server has
 * nothing to show: every list is empty, everything asked for by its id is not found, every write is refused,
 * nothing is served — and nothing is asked of the server.
 */
class AbsEmptyAnswersTest {

    @Test
    fun `the library is empty, and what is asked by its id is not found`() = runBlocking {
        val library = EmptyAbsLibrary()

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
        assertThat(library.seriesBooks(SeriesId("ser_1"))).isEqualTo(NotFound)
    }

    @Test
    fun `no file is read, no place is kept and none is saved`() = runBlocking {
        val reader = EmptyAbsReader()

        assertThat(reader.fileUrl(File, audioless = false)).isNull()
        assertThat(reader.position(File)).isEqualTo(NotFound)
        assertThat(reader.savePosition(File, cfi = null, percent = 50.0, page = 3)).isEqualTo(NotFound)
        assertThat(reader.finishedReading(Book)).isEqualTo(ApiResult.Success(null))
        assertThat(reader.nextVolume(SeriesId("ser_1"), Book)).isEqualTo(ApiResult.Success(null))
        assertThat(reader.download(File, audioless = false, ByteArrayOutputStream()) {}).isEqualTo(NotFound)
    }

    @Test
    fun `no page, no audio and no marks are served`() = runBlocking {
        assertThat(EmptyAbsAudio().assetUrl(Book, "a")).isNull()
        assertThat(EmptyAbsAudio().manifest(Book)).isEqualTo(NotFound)
        assertThat(EmptyAbsAudio().playbackState(Book)).isEqualTo(ApiResult.Success(null))
        assertThat(EmptyAbsAudioBookmarks().list(Book)).isEqualTo(Nothing)
        assertThat(EmptyAbsMarks().bookmarks(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyAbsMarks().highlights(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyAbsMarks().addBookmark(Book, File, "cfi", "title")).isEqualTo(NotFound)
        assertThat(EmptyAbsReadAlong().trackUrl(Book, File, "a.mp3")).isNull()
        assertThat(NoAbsPages().servesByPages(PagedKind.Comic)).isFalse()
        assertThat(NoAbsPages().pageUrl(PagedKind.Comic, File, 0)).isNull()
        assertThat(NoAbsPages().pageCount(PagedKind.Comic, File)).isEqualTo(NotFound)
        assertThat(NoAbsPages().page(PagedKind.Comic, File, 0, PageSink { ByteArrayOutputStream() }))
            .isEqualTo(NotFound)
        assertThat(NoAbsDownloadLocations().url(Book, FileSource.Whole(File))).isNull()
    }

    @Test
    fun `the history is an empty list, with nothing more to show`() = runBlocking {
        val history = EmptyAbsHistory()

        assertThat(history.capabilities).isEqualTo(HistoryCapabilities(false, false, false, false, false, false, false))
        val page = (history.log(HistoryPeriod(null), emptySet(), true, offset = 0, limit = 20) as ApiResult.Success)
        assertThat(page.value.records).isEmpty()
        assertThat(page.value.total).isEqualTo(0)
        assertThat(history.marks(ReadId("r_9"))).isEqualTo(NotFound)
        assertThat(history.setRating(ReadId("r_9"), stars = 4)).isEqualTo(NotFound)
    }

    @Test
    fun `a refusal of a file is read from its status`() {
        val refusals = AbsDownloadRefusals()

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
            val client = OkHttpClient.Builder().addInterceptor(AbsPassThrough).build()

            val answer = client.newCall(Request.Builder().url(server.url("/api/items/li_8f2a/file")).build()).execute()

            assertThat(answer.body.string()).isEqualTo("ok")
        } finally {
            server.close()
        }
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://abs.example.com/api/items/li_8f2a/file").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body("".toResponseBody())
        .build()
}
