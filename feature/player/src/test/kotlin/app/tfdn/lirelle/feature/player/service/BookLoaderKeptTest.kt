package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.library.model.MediumCover
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** An audiobook the device keeps: what the download kept stands in for what the server cannot give. */
@RunWith(AndroidJUnit4::class)
class BookLoaderKeptTest {

    private val served = Manifests.twoFiles().copy(title = "The Long Road, as served")
    private val keptManifest = Manifests.twoFiles().copy(contentVersion = "rev-kept", title = "The Long Road, as kept")
    private val page = sampleBookDetail(id = "42").copy(coverUrl = "https://books.example/c/42")
    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(served) }
    private val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(page) }
    private val store = InMemoryPlaybackStore()
    private val downloads = FakeDownloads()
    private val data = FakeKeptBookData().apply {
        manifests[BookId("42")] = keptManifest
        details[BookId("42")] = page.copy(coverUrl = "https://books.example/kept/42")
    }
    private val loader = BookLoader(
        audio, library, store, InMemoryPlayerSettingsStore(), partLabel = { "Part $it" },
        kept = KeptBooks(downloads, data, BookHolds.None),
    )

    private fun unreachable(error: ApiError = ApiError.ServerUnreachable) {
        audio.manifestResult = ApiResult.Failure(error)
        audio.playbackStateResult = ApiResult.Failure(error)
        library.bookResult = ApiResult.Failure(error)
    }

    private fun keptAt(bookTimeMs: Long) {
        val at = Instant.parse("2026-11-15T09:00:00Z")
        store.positions[BookId("42")] =
            LocalPlayback(BookId("42"), "a1", bookTimeMs, bookTimeMs, at, contentVersion = "rev-kept")
    }

    @Test
    fun `without the server, a downloaded audiobook plays from the manifest, the page and the place kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        unreachable()
        keptAt(45_000)

        val book = loader.load(BookId("42"), fromStart = false)!!

        assertThat(book.playlist.manifest).isEqualTo(keptManifest)
        assertThat(book.playlist.items).hasSize(2)
        assertThat(book.playlist.coverUrl).isEqualTo("https://books.example/kept/42")
        assertThat(book.seriesId).isEqualTo(SeriesId("4"))
        assertThat(book.startMs).isEqualTo(45_000)
        assertThat(book.kept).isTrue()
    }

    @Test
    fun `a downloaded audiobook whose chapters have no titles is in parts, as when it is streamed`() = runTest {
        val untitled = keptManifest.copy(chapters = keptManifest.chapters.map { it.copy(title = "") })
        audio.manifestResult = ApiResult.Success(untitled.copy(contentVersion = "rev-1"))
        val streamed = loader.load(BookId("42"), fromStart = false)!!
        data.manifests[BookId("42")] = untitled
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        unreachable()

        val downloaded = loader.load(BookId("42"), fromStart = false)!!

        assertThat(streamed.kept).isFalse()
        assertThat(downloaded.kept).isTrue()
        assertThat(listOf(streamed, downloaded).map { it.playlist.inParts }).containsExactly(true, true)
        assertThat(downloaded.playlist.chapters.map { it.title })
            .containsExactly("Part 1", "Part 2", "Part 3").inOrder()
    }

    /** The server is there, with the audiobook's own cover; then it is not. */
    private suspend fun playlistsWithTheServerAndWithout(): List<BookPlaylist> {
        val own = MediumCover("https://books.example/c/42?medium=audio", "https://books.example/t/42?medium=audio")
        library.bookResult = ApiResult.Success(page.copy(audioCover = own))
        val online = loader.load(BookId("42"), fromStart = false)!!.playlist
        unreachable()
        return listOf(online, loader.load(BookId("42"), fromStart = false)!!.playlist)
    }

    private fun assertShows(playlists: List<BookPlaylist>, cover: File) {
        val onDevice = Uri.fromFile(cover)
        for (playlist in playlists) {
            assertThat(playlist.coverUrl).isEqualTo(onDevice.toString())
            assertThat(playlist.artworkUrl).isEqualTo(onDevice.toString())
            assertThat(playlist.items.map { it.mediaMetadata.artworkUri }.distinct()).containsExactly(onDevice)
        }
    }

    @Test
    fun `a downloaded audiobook shows its own cover kept on the device, rather than the book's`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        data.covers[BookId("42")] = File.createTempFile("cover", null).apply { deleteOnExit() }
        val audioCover = File.createTempFile("audio-cover", null).apply { deleteOnExit() }
        data.audioCovers[BookId("42")] = audioCover

        assertShows(playlistsWithTheServerAndWithout(), audioCover)
    }

    @Test
    fun `a downloaded audiobook without its own cover kept shows the book's cover kept on the device`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        val cover = File.createTempFile("cover", null).apply { deleteOnExit() }
        data.covers[BookId("42")] = cover

        assertShows(playlistsWithTheServerAndWithout(), cover)
    }

    @Test
    fun `a downloaded audiobook whose cover was not kept shows the server's, as one that is not downloaded`() =
        runTest {
            downloads.has(BookId("42"), DownloadFormat.Audiobook)

            val playlist = loader.load(BookId("42"), fromStart = false)!!.playlist

            assertThat(playlist.artworkUrl).isEqualTo("https://books.example/c/42")
            assertThat(playlist.items.map { it.mediaMetadata.artworkUri.toString() }.distinct())
                .containsExactly("https://books.example/c/42")
        }

    @Test
    fun `a downloaded audiobook with no cover kept shows its own cover from the server when it has one`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        val own = MediumCover("https://books.example/c/42?medium=audio", "https://books.example/t/42?medium=audio")
        library.bookResult = ApiResult.Success(page.copy(audioCover = own))

        val playlist = loader.load(BookId("42"), fromStart = false)!!.playlist

        assertThat(playlist.coverUrl).isEqualTo(own.url)
        assertThat(playlist.artworkUrl).isEqualTo(own.thumbnailUrl)
    }

    @Test
    fun `an audiobook that is not downloaded whole shows the server's cover, whatever the device kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook, FormatState.Downloading(10, 100))
        data.covers[BookId("42")] = File.createTempFile("cover", null).apply { deleteOnExit() }
        data.audioCovers[BookId("42")] = File.createTempFile("audio-cover", null).apply { deleteOnExit() }

        val playlist = loader.load(BookId("42"), fromStart = false)!!.playlist

        assertThat(playlist.coverUrl).isEqualTo("https://books.example/c/42")
        assertThat(playlist.artworkUrl).isEqualTo("https://books.example/c/42")
    }

    @Test
    fun `without the server nor a place kept, a downloaded audiobook starts at its beginning`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        unreachable()

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(0)
    }

    @Test
    fun `a book gone from the server still plays from what was kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        unreachable(ApiError.NotFound)

        assertThat(loader.load(BookId("42"), fromStart = false)!!.playlist.manifest).isEqualTo(keptManifest)
    }

    @Test
    fun `any other failure of the server is not answered by what was kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        unreachable(ApiError.ServerFailed)

        assertThat(loader.load(BookId("42"), fromStart = false)).isNull()
    }

    @Test
    fun `the manifest kept is played while the server has another content version, with its page and place`() =
        runTest {
            downloads.has(BookId("42"), DownloadFormat.Audiobook)
            val at = Instant.parse("2026-11-15T08:00:00Z")
            audio.playbackStateResult = ApiResult.Success(PlaybackState("a2", 30_000, 0.0, false, at, "v7", "rev-1"))

            val book = loader.load(BookId("42"), fromStart = false)!!

            assertThat(book.playlist.manifest).isEqualTo(keptManifest)
            assertThat(book.playlist.coverUrl).isEqualTo("https://books.example/c/42")
            assertThat(book.startMs).isEqualTo(130_000)
            assertThat(book.kept).isTrue()
        }

    @Test
    fun `the server's manifest is played when its content version is the one kept`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        data.manifests[BookId("42")] = keptManifest.copy(contentVersion = served.contentVersion)

        val book = loader.load(BookId("42"), fromStart = false)!!

        assertThat(book.playlist.manifest).isEqualTo(served)
        assertThat(book.kept).isTrue()
    }

    @Test
    fun `a download without its manifest plays the server's`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        data.manifests.clear()

        assertThat(loader.load(BookId("42"), fromStart = false)!!.playlist.manifest).isEqualTo(served)
    }

    @Test
    fun `an audiobook that is not downloaded whole, or is being deleted, plays the server's manifest`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Narration)
        downloads.has(BookId("42"), DownloadFormat.Audiobook, FormatState.Downloading(10, 100))
        val partial = loader.load(BookId("42"), fromStart = false)!!
        downloads.has(BookId("42"), DownloadFormat.Audiobook, removing = true)
        val removing = loader.load(BookId("42"), fromStart = false)!!

        assertThat(partial.playlist.manifest).isEqualTo(served)
        assertThat(partial.kept).isFalse()
        assertThat(removing.playlist.manifest).isEqualTo(served)
        assertThat(removing.kept).isFalse()
    }

    @Test
    fun `without the server, an audiobook that is not downloaded whole, or is being deleted, fails as before`() =
        runTest {
            unreachable()
            keptAt(45_000)

            assertThat(loader.load(BookId("42"), fromStart = false)).isNull()
            downloads.has(BookId("42"), DownloadFormat.Audiobook, FormatState.Waiting(WaitReason.NoNetwork, 10, 100))
            assertThat(loader.load(BookId("42"), fromStart = false)).isNull()
            downloads.has(BookId("42"), DownloadFormat.Audiobook, removing = true)
            assertThat(loader.load(BookId("42"), fromStart = false)).isNull()
        }
}
