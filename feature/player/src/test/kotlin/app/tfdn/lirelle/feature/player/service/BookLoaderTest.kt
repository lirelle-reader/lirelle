package app.tfdn.lirelle.feature.player.service

import kotlinx.coroutines.async
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookLoaderTest {

    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }
    private val library = FakeLibraryRepository().apply {
        bookResult = ApiResult.Success(sampleBookDetail(id = "42").copy(coverUrl = "https://books.example/c/42"))
    }
    private val store = InMemoryPlaybackStore()
    private val settings = InMemoryPlayerSettingsStore(PlayerSettings(lastSpeed = 1.25f))
    /** The books with a position still waiting to be sent. */
    private val waiting = mutableSetOf<BookId>()

    /** The books against which a state written elsewhere stands unanswered. */
    private val standing = mutableSetOf<BookId>()
    private val loader =
        BookLoader(audio, library, store, settings, waiting = { it in waiting }, standing = { it in standing }) {
            "Part $it"
        }

    private fun serverAt(
        assetId: String,
        positionMs: Long,
        at: String = "2026-11-15T08:00:00Z",
        version: String = "v7",
    ) {
        audio.playbackStateResult = ApiResult.Success(
            PlaybackState(assetId, positionMs, 0.0, false, Instant.parse(at), version, "rev-1"),
        )
    }

    private fun keptAt(bookTimeMs: Long, at: String) {
        store.positions[BookId("42")] =
            LocalPlayback(BookId("42"), "a1", bookTimeMs, bookTimeMs, Instant.parse(at), contentVersion = "rev-1")
    }

    @Test
    fun `a book opens where the server says, and its version is the one written over`() = runTest {
        serverAt("a2", 30_000)

        val book = loader.load(BookId("42"), fromStart = false)!!

        assertThat(book.startMs).isEqualTo(130_000)
        assertThat(book.playlist.coverUrl).isEqualTo("https://books.example/c/42")
        assertThat(book.seriesId).isEqualTo(SeriesId("4"))
        assertThat(store.versions[BookId("42")]).isEqualTo("v7")
    }

    @Test
    fun `the player shows the audiobook's own cover, in full, and small for the notification`() = runTest {
        val cover = MediumCover("https://books.example/c/42?medium=audio", "https://books.example/t/42?medium=audio")
        val page = sampleBookDetail(id = "42").copy(coverUrl = "https://books.example/c/42", audioCover = cover)
        library.bookResult = ApiResult.Success(page)

        val playlist = loader.load(BookId("42"), fromStart = false)!!.playlist

        assertThat(playlist.coverUrl).isEqualTo(cover.url)
        assertThat(playlist.artworkUrl).isEqualTo(cover.thumbnailUrl)
        assertThat(playlist.items.map { it.mediaMetadata.artworkUri.toString() }.distinct())
            .containsExactly(cover.thumbnailUrl)
    }

    @Test
    fun `without an audiobook cover, the player shows the book's`() = runTest {
        val playlist = loader.load(BookId("42"), fromStart = false)!!.playlist

        assertThat(playlist.coverUrl).isEqualTo("https://books.example/c/42")
        assertThat(playlist.artworkUrl).isEqualTo("https://books.example/c/42")
    }

    @Test
    fun `a state from elsewhere the user chose (go there) opens there, a newer kept position notwithstanding`() =
        runTest {
            // The last write taken was at 7:00, the other device wrote at 8:00 and was followed, the kept tick is 9:00.
            store.written[BookId("42")] = Instant.parse("2026-11-15T07:00:00Z")
            store.versions[BookId("42")] = "v7"
            serverAt("a2", 30_000, at = "2026-11-15T08:00:00Z")
            keptAt(45_000, "2026-11-15T09:00:00Z")

            val book = loader.load(BookId("42"), fromStart = false)!!

            assertThat(book.startMs).isEqualTo(130_000)
        }

    @Test
    fun `a newer kept position opens while the state from elsewhere was not chosen, and its version is not taken`() =
        runTest {
            store.written[BookId("42")] = Instant.parse("2026-11-15T07:00:00Z")
            store.versions[BookId("42")] = "v5"
            serverAt("a2", 30_000, at = "2026-11-15T08:00:00Z")
            keptAt(45_000, "2026-11-15T09:00:00Z")

            assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(45_000)
            assertThat(store.versions[BookId("42")]).isEqualTo("v5")
        }

    @Test
    fun `while a state from elsewhere stands unanswered, the kept position opens, even an older one`() = runTest {
        standing += BookId("42")
        serverAt("a2", 30_000, at = "2026-11-15T08:00:00Z")
        keptAt(45_000, "2026-11-15T07:59:00Z")

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(45_000)
    }

    @Test
    fun `a kept position still waiting to be sent opens there, whoever wrote the server's`() = runTest {
        waiting += BookId("42")
        store.written[BookId("42")] = Instant.parse("2026-11-15T07:00:00Z")
        serverAt("a2", 30_000, at = "2026-11-15T08:00:00Z")
        keptAt(45_000, "2026-11-15T09:00:00Z")

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(45_000)
    }

    @Test
    fun `a kept position newer than the device's own last write on the server opens there`() = runTest {
        store.written[BookId("42")] = Instant.parse("2026-11-15T08:00:00Z")
        serverAt("a2", 30_000, at = "2026-11-15T08:00:00Z")
        keptAt(45_000, "2026-11-15T09:00:00Z")

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(45_000)
    }

    @Test
    fun `without the server, it opens at the position kept on the device`() = runTest {
        audio.playbackStateResult = ApiResult.Failure(ApiError.ServerUnreachable)
        keptAt(45_000, "2026-11-15T08:00:00Z")

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(45_000)
    }

    @Test
    fun `a position kept on the device but not sent yet wins over an older one on the server`() = runTest {
        waiting += BookId("42")
        serverAt("a1", 10_000, at = "2026-11-15T08:00:00Z")
        keptAt(50_000, "2026-11-15T09:00:00Z")

        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(50_000)
    }

    @Test
    fun `with neither, from the beginning, and from the start always from the beginning`() = runTest {
        assertThat(loader.load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(0)
        serverAt("a2", 30_000)
        assertThat(loader.load(BookId("42"), fromStart = true)!!.startMs).isEqualTo(0)
        assertThat(loader.load(BookId("42"), fromStart = false, at = 99_000)!!.startMs).isEqualTo(99_000)
    }

    @Test
    fun `the book's own speed, or the last one chosen for a new book`() = runTest {
        assertThat(loader.load(BookId("42"), fromStart = false)!!.speed).isEqualTo(1.25f)
        settings.speeds[BookId("42")] = 2f
        assertThat(loader.load(BookId("42"), fromStart = false)!!.speed).isEqualTo(2f)
    }

    @Test
    fun `a book whose manifest cannot be read does not load, one without its page still does`() = runTest {
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(loader.load(BookId("42"), fromStart = false)!!.playlist.coverUrl).isNull()

        audio.manifestResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(loader.load(BookId("42"), fromStart = false)).isNull()
    }

    @Test
    fun `the manifest, the page and the server's position are asked at the same time`() = runTest {
        audio.gate = kotlinx.coroutines.CompletableDeferred()
        library.gate = audio.gate

        val loading = async { loader.load(BookId("42"), fromStart = false) }
        testScheduler.runCurrent()

        assertThat(audio.calls).containsAtLeast("manifest 42", "playbackState 42")
        assertThat(library.calls).contains("book 42")
        audio.gate!!.complete(Unit)
        assertThat(loading.await()).isNotNull()
    }
}
