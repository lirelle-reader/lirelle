package app.tfdn.lirelle.source.komga.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.network.KomgaAccountPictures
import app.tfdn.lirelle.source.komga.network.PassThrough
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

private val Book = BookId("0B00K")
private val File = FileId("0F1LE")

/**
 * Until the library is browsed (3a2) and read (3b), a Komga profile's server has nothing to show: every list is
 * empty, everything asked for by its id is not found, nothing is served — and nothing is asked of the server.
 */
class KomgaEmptyAnswersTest {

    @Test
    fun `the library is empty, and what is asked by its id is not found`() = runBlocking {
        val library = EmptyKomgaLibrary()

        assertThat(library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()).isEmpty()
        assertThat(library.books(BookListSource.AllBooks, BookListRequest()).total.value).isEqualTo(0)
        assertThat((library.bookPage(BookListRequest(), page = 0, size = 60) as ApiResult.Success).value.items)
            .isEmpty()
        assertThat(library.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot()).isEmpty()
        assertThat(library.series()).isEqualTo(ApiResult.Success(emptyList<Any>()))
        assertThat(library.genres()).isEqualTo(ApiResult.Success(emptyList<Any>()))
        assertThat((library.shelves() as ApiResult.Success).value.isEmpty).isTrue()
        assertThat((library.search("dune") as ApiResult.Success).value.isEmpty).isTrue()
        assertThat((library.homeRow(HomeRowKind.Continue) as ApiResult.Success).value.books).isEmpty()
        assertThat(library.book(Book)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(library.seriesBooks(SeriesId("0S"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `no audio and no marks are served`() = runBlocking {
        assertThat(EmptyKomgaAudio().assetUrl(Book, "a")).isNull()
        assertThat(EmptyKomgaAudio().manifest(Book)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(EmptyKomgaAudioBookmarks().list(Book)).isEqualTo(ApiResult.Success(emptyList<Any>()))
        assertThat(EmptyKomgaMarks().bookmarks(Book, File)).isEqualTo(ApiResult.Success(emptyList<Any>()))
        assertThat(EmptyKomgaMarks().highlights(Book, File)).isEqualTo(ApiResult.Success(emptyList<Any>()))
        assertThat(EmptyKomgaReadAlong().trackUrl(Book, File, "a.mp3")).isNull()
        assertThat(KomgaAccountPictures.pictureUrl("https://bd.example.com", "/avatar")).isNull()
    }

    @Test
    fun `no request is a long transfer, each goes on as it is`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 200, body = "ok"))
            val client = OkHttpClient.Builder().addInterceptor(PassThrough).build()

            val answer = client.newCall(Request.Builder().url(server.url("/api/v1/books/1/file")).build()).execute()

            assertThat(answer.body.string()).isEqualTo("ok")
        } finally {
            server.close()
        }
    }

}
