package app.tfdn.lirelle.source.bookorbit.downloads

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.source.bookorbit.comics.BookOrbitPageSource
import app.tfdn.lirelle.source.bookorbit.comics.ComicsApi
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

private const val Server = "https://books.example"
private val TheBook = BookId("3")

/** Where a BookOrbit server serves each kept file: the routes its reader, its player and its page reader use. */
class BookOrbitDownloadLocationsTest {

    private var signedIn = true
    private val reader = FakeReaderRepository().apply { server = Server }
    private val pages = BookOrbitPageSource(
        api = ApiProvider(OkHttpClient(), ApiJson, ComicsApi::class.java),
        errors = ErrorMapper(ApiJson),
        serverUrl = { Server.takeIf { signedIn } },
    )
    private val locations = BookOrbitDownloadLocations(
        reader = reader,
        audio = FakeAudioRepository().apply { serverUrl = Server },
        readAlong = FakeReadAlongRepository().apply { serverUrl = Server },
        pages = pages,
    )

    @Test
    fun `the pages of a comic are under the comics' routes, those of a DjVu under its own`() {
        val comic = List(3) { locations.url(TheBook, FileSource.Page(FileId("11"), PagedKind.Comic, it)) }
        val djvu = List(3) { locations.url(TheBook, FileSource.Page(FileId("31"), PagedKind.Djvu, it)) }

        assertThat(comic).containsExactly(
            "$Server/api/v1/cbz/files/11/pages/0",
            "$Server/api/v1/cbz/files/11/pages/1",
            "$Server/api/v1/cbz/files/11/pages/2",
        ).inOrder()
        assertThat(djvu).containsExactly(
            "$Server/api/v1/djvu/files/31/pages/0",
            "$Server/api/v1/djvu/files/31/pages/1",
            "$Server/api/v1/djvu/files/31/pages/2",
        ).inOrder()
    }

    @Test
    fun `a page of a PDF has no address, nor a page with no session`() {
        assertThat(locations.url(TheBook, FileSource.Page(FileId("11"), PagedKind.Pdf, 0))).isNull()

        signedIn = false

        assertThat(locations.url(TheBook, FileSource.Page(FileId("11"), PagedKind.Comic, 0))).isNull()
    }

    @Test
    fun `a whole file, an audio asset and a read-along track are where the repositories say`() {
        assertThat(locations.url(TheBook, FileSource.Whole(FileId("12"))))
            .isEqualTo(reader.fileUrl(FileId("12"), audioless = false))
        assertThat(locations.url(TheBook, FileSource.Audioless(FileId("12"))))
            .isEqualTo(reader.fileUrl(FileId("12"), audioless = true))
        assertThat(locations.url(TheBook, FileSource.AudioAsset("a1")))
            .isEqualTo("$Server/audio/3/a1")
        assertThat(locations.url(TheBook, FileSource.ReadAlongTrack(FileId("12"), "audio/one.mp3")))
            .isEqualTo("$Server/narration/3/12/audio/one.mp3")
    }
}
