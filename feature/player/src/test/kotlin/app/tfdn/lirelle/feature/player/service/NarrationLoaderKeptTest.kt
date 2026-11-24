package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** A narration the device keeps: its own map is the one played, and its page stands in for the server's. */
@RunWith(AndroidJUnit4::class)
class NarrationLoaderKeptTest {

    private val served = ReadAlongMaps.inventedMap()
    private val keptMap = ReadAlongMaps.inventedMap(contentVersion = "kept")
    private val maps = FakeMapStore(served)
    private val page = sampleBookDetail(id = "42").copy(title = "The Long Road")
    private val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(page) }
    private val reader = FakeReaderRepository()
    private val narrations = InMemoryNarrationStore()
    private val downloads = FakeDownloads()
    private val data = FakeKeptBookData().apply {
        maps[BookId("42")] = keptMap
        details[BookId("42")] = page.copy(title = "The Long Road, as kept")
    }
    private val loader = NarrationLoader(
        maps, library, reader, narrations, InMemoryPlayerSettingsStore(), FakeReadAlongRepository(),
        KeptBooks(downloads, data, BookHolds.None),
    )

    private fun unreachable() {
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        reader.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        maps.map = null
    }

    @Test
    fun `a downloaded narration shows the cover kept on the device, with the server or without`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Narration)
        val cover = File.createTempFile("cover", null).apply { deleteOnExit() }
        data.covers[BookId("42")] = cover
        library.bookResult = ApiResult.Success(page.copy(coverUrl = "https://books.example/c/42"))
        val online = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.playlist
        unreachable()
        val offline = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.playlist

        val onDevice = Uri.fromFile(cover)
        for (playlist in listOf(online, offline)) {
            assertThat(playlist.coverUrl).isEqualTo(onDevice.toString())
            assertThat(playlist.artworkUrl).isEqualTo(onDevice.toString())
            assertThat(playlist.items.map { it.mediaMetadata.artworkUri }.distinct()).containsExactly(onDevice)
        }
    }

    @Test
    fun `a narration shows the server's cover when none was kept, or when it is not downloaded`() = runTest {
        library.bookResult = ApiResult.Success(page.copy(coverUrl = "https://books.example/c/42"))
        downloads.has(BookId("42"), DownloadFormat.Narration)
        val noCover = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.playlist
        downloads.has(BookId("42"), DownloadFormat.Narration, FormatState.Downloading(10, 100))
        data.covers[BookId("42")] = File.createTempFile("cover", null).apply { deleteOnExit() }
        val streamed = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.playlist

        assertThat(noCover.artworkUrl).isEqualTo("https://books.example/c/42")
        assertThat(streamed.artworkUrl).isEqualTo("https://books.example/c/42")
        assertThat(streamed.items.map { it.mediaMetadata.artworkUri.toString() }.distinct())
            .containsExactly("https://books.example/c/42")
    }

    @Test
    fun `a downloaded narration plays the map kept, with the server there too`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Narration)

        val book = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!

        assertThat(book.source).isEqualTo(BookSource.Narration(FileId("1"), keptMap))
        assertThat(book.playlist.manifest.title).isEqualTo("The Long Road")
        assertThat(maps.asked).isEmpty()
        assertThat(book.kept).isTrue()
    }

    @Test
    fun `without the server, a downloaded narration plays from the map, the page and the place kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Narration)
        unreachable()
        narrations.narrations[FileId("1")] = LocalNarration(
            fileId = FileId("1"),
            place = NarrationPlace(0.0, "text/three.xhtml#s5", 3),
            percent = 10.0,
            at = Instant.parse("2026-11-15T09:00:00Z"),
        )

        val book = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!

        assertThat(book.source).isEqualTo(BookSource.Narration(FileId("1"), keptMap))
        assertThat(book.playlist.manifest.title).isEqualTo("The Long Road, as kept")
        assertThat(book.startMs).isEqualTo(19_000)
        assertThat(book.kept).isTrue()
    }

    @Test
    fun `a download without its map plays the device's`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Narration)
        data.maps.clear()

        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.source)
            .isEqualTo(BookSource.Narration(FileId("1"), served))
    }

    @Test
    fun `a narration that is not downloaded whole, or is being deleted, plays the device's map`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Text)
        downloads.has(BookId("42"), DownloadFormat.Narration, FormatState.Downloading(10, 100))
        val partial = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!
        downloads.has(BookId("42"), DownloadFormat.Narration, removing = true)
        val removing = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!

        assertThat(partial.source).isEqualTo(BookSource.Narration(FileId("1"), served))
        assertThat(partial.kept).isFalse()
        assertThat(removing.source).isEqualTo(BookSource.Narration(FileId("1"), served))
        assertThat(removing.kept).isFalse()
    }

    @Test
    fun `without the server, a narration that is not downloaded whole, or is being deleted, fails as before`() =
        runTest {
            unreachable()

            assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)).isNull()
            downloads.has(BookId("42"), DownloadFormat.Narration, FormatState.Queued)
            assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)).isNull()
            downloads.has(BookId("42"), DownloadFormat.Narration, removing = true)
            assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)).isNull()
        }
}
