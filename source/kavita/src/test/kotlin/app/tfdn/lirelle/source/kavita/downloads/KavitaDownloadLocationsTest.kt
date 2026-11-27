package app.tfdn.lirelle.source.kavita.downloads

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.source.kavita.comics.KavitaPageSource
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private val Book = BookId("c44")

/** A chapter of the test server: library 1, series 7, volume 7, chapter 44, file 44. */
private val Chapter = FileId("1-7-7-44-44")

private const val Server = "https://kavita.example.com"

/** A Kavita book is kept as its comic pages, fetched where the comics reader fetches them, or as its whole file. */
class KavitaDownloadLocationsTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes

    @Before
    fun setUp() {
        server.start()
        routes = KavitaRoutes(server)
    }

    @After
    fun tearDown() = server.close()

    private fun locations(serverUrl: String = Server): KavitaDownloadLocations {
        val pages = KavitaPageSource(routes.readingApi(), KavitaErrors()) { serverUrl }
        return KavitaDownloadLocations(pages) { serverUrl }
    }

    @Test
    fun `a comic's page is fetched where the comics reader fetches it`() {
        assertThat(locations().url(Book, FileSource.Page(Chapter, PagedKind.Comic, 3)))
            .isEqualTo("$Server/api/Reader/image?chapterId=44&page=3")
    }

    @Test
    fun `a whole file is Kavita's download of its chapter`() {
        assertThat(locations().url(Book, FileSource.Whole(Chapter)))
            .isEqualTo("$Server/api/Download/chapter?chapterId=44")
    }

    @Test
    fun `a whole file whose id is not Kavita's has no address`() {
        assertThat(locations().url(Book, FileSource.Whole(FileId("44")))).isNull()
    }

    @Test
    fun `a server under a base path keeps it`() {
        assertThat(locations("https://example.com/kavita").url(Book, FileSource.Whole(Chapter)))
            .isEqualTo("https://example.com/kavita/api/Download/chapter?chapterId=44")
    }

    @Test
    fun `Kavita has no audio, no narration and no audioless file`() {
        val locations = locations()

        assertThat(locations.url(Book, FileSource.Audioless(Chapter))).isNull()
        assertThat(locations.url(Book, FileSource.AudioAsset("a"))).isNull()
        assertThat(locations.url(Book, FileSource.ReadAlongTrack(Chapter, "a.mp3"))).isNull()
    }
}
