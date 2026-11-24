package app.tfdn.lirelle.source.komga.downloads

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.comics.KomgaPageSource
import app.tfdn.lirelle.source.komga.comics.KomgaPagesApi
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private val Book = BookId("0BK1A")

/** A comic of Komga is one file, whose id is the book's. */
private val Album = FileId("0BK1A")

/** Two pages, written by hand: a JPEG, then a JPEG XL the device cannot draw. */
private const val PagesJson = """[{"number":1,"fileName":"001.jpg","mediaType":"image/jpeg","sizeBytes":4},""" +
    """{"number":2,"fileName":"002.jxl","mediaType":"image/jxl","sizeBytes":4}]"""

/** A Komga comic is kept page by page, each fetched where the comics reader fetches it; nothing else is served. */
class KomgaDownloadLocationsTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var pages: KomgaPageSource
    private lateinit var locations: KomgaDownloadLocations

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        pages = KomgaPageSource(KomgaApis(OkHttpClient(), KomgaJson, KomgaPagesApi::class.java), KomgaErrors()) { url }
        locations = KomgaDownloadLocations(pages)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a page is fetched at its address, as a JPEG when the device cannot draw it`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        assertThat(pages.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(2))

        assertThat(locations.url(Book, FileSource.Page(Album, PagedKind.Comic, 0)))
            .isEqualTo("$url/api/v1/books/0BK1A/pages/1")
        assertThat(locations.url(Book, FileSource.Page(Album, PagedKind.Comic, 1)))
            .isEqualTo("$url/api/v1/books/0BK1A/pages/2?convert=jpeg")
        // Komga serves no pages of a DjVu, nor any file whole, audio or narration.
        assertThat(locations.url(Book, FileSource.Page(Album, PagedKind.Djvu, 0))).isNull()
        assertThat(locations.url(Book, FileSource.Whole(Album))).isNull()
        assertThat(locations.url(Book, FileSource.Audioless(Album))).isNull()
        assertThat(locations.url(Book, FileSource.AudioAsset("a"))).isNull()
        assertThat(locations.url(Book, FileSource.ReadAlongTrack(Album, "a.mp3"))).isNull()
    }
}
