package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.Books
import app.tfdn.lirelle.core.downloads.engine.FakeServedPages
import app.tfdn.lirelle.core.downloads.engine.FormatPlanner
import app.tfdn.lirelle.core.downloads.engine.PagedPlans
import app.tfdn.lirelle.core.downloads.engine.PlannedData
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.engine.TestLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.offline.DevicePlaces
import app.tfdn.lirelle.core.downloads.offline.InMemoryNarrations
import app.tfdn.lirelle.core.downloads.offline.InMemoryPlaybackQueue
import app.tfdn.lirelle.core.downloads.offline.InMemoryPlaybacks
import app.tfdn.lirelle.core.downloads.offline.KeptFixture
import app.tfdn.lirelle.core.downloads.offline.KeptPositions
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.downloads.offline.WaitingBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val OldTrack = "OEBPS/Audio/track002.mp4"
private const val NewTrack = "OEBPS/Audio/track002-again.mp4"

private val TheBook = BookId("1")

/**
 * Until the files of an update take the place of the kept ones, the kept copy opens as it was fetched: what it
 * opens with, the audiobook's manifest, the narration's map and the page, describes the files on the device.
 * The plans are the real ones, over a fake server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptCopyUntilSwapTest {

    @get:Rule
    internal val fixture = KeptFixture()

    private val library = FakeLibraryRepository()
    private val reader = FakeReaderRepository()
    private val audio = FakeAudioRepository()
    private val keptData by lazy { fixture.kept() }

    private fun planner(registry: DownloadRegistry, files: DownloadFiles): FormatPlanner {
        val places = DevicePlaces(
            positions = InMemoryPositionStore(),
            outbox = InMemoryOutbox(),
            narrations = InMemoryNarrations(),
            playback = InMemoryPlaybacks(),
            playbackOutbox = InMemoryPlaybackQueue(),
        )
        val data = PlannedData(keptData, WaitingBookData(files, fixture.maps), registry)
        val positions = KeptPositions(registry, reader, audio, keptData, places)
        val paged = PagedPlans(FakeServedPages(), registry)
        return FormatPlanner(library, audio, fixture.readAlong, fixture.maps, data, positions, paged)
    }

    private fun queueIn(scope: TestScope) =
        QueueFixture(scope, checkNotNull(fixture.files.bookDir(TheBook).parentFile), server = ::planner)

    private fun keptTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest { queueIn(this).test(this) }

    /** What the reader and the player ask for a file's address, without the network. */
    private fun QueueFixture.downloaded(scope: TestScope): RegistryDownloadedFiles {
        val locations = TestLocations({ reader.server }, reader, audio, fixture.readAlong)
        return RegistryDownloadedFiles(registry, files, locations).also {
            it.start(scope.backgroundScope)
            scope.runCurrent()
        }
    }

    private fun serve(book: BookDetail) {
        library.bookResult = ApiResult.Success(book)
    }

    // --- audiobook

    /** Three small files; the second version has another file in the place of the second. */
    private val manifest = Recorded.manifest("mp3").let { recorded ->
        val assets = recorded.assets.take(3).map { it.copy(sizeBytes = 10) }
        val chapters = recorded.chapters.filter { chapter -> assets.any { it.assetId == chapter.assetId } }
        recorded.copy(bookId = TheBook, assets = assets, chapters = chapters)
    }
    private val newerManifest = manifest.copy(
        contentVersion = "newer",
        assets = manifest.assets.map {
            if (it.sequence == 1) it.copy(assetId = "aud_900", etag = "changed", sizeBytes = 20) else it
        },
    )

    private fun QueueFixture.assertPlays(manifest: AudioManifest, scope: TestScope) {
        val downloaded = downloaded(scope)
        manifest.assets.forEach { asset ->
            assertThat(downloaded.audio(checkNotNull(audio.assetUrl(TheBook, asset.assetId)))).isNotNull()
        }
    }

    /** Book 1 keeps the audiobook; the server then has the newer one. */
    private suspend fun QueueFixture.audiobookChanged() {
        serve(Books.book(Books.audio(50, 30), id = "1"))
        audio.manifestResult = ApiResult.Success(manifest)
        queue.ask(TheBook, setOf(Audiobook))
        runner.runUntilIdle()
        audio.manifestResult = ApiResult.Success(newerManifest)
    }

    @Test
    fun `a check that finds a newer audiobook leaves the kept manifest, whose files all play`() = keptTest {
        audiobookChanged()

        updates.checkBook(TheBook)

        assertThat(update(TheBook, Audiobook)?.version).isEqualTo("newer")
        assertThat(keptData.manifest(TheBook)).isEqualTo(manifest)
        assertPlays(manifest, it)
    }

    @Test
    fun `the newer manifest takes the place of the kept one with the files, when the book closes`() = keptTest {
        audiobookChanged()
        remover.hold(TheBook)
        askUpdate(TheBook)
        runner.runUntilIdle()

        assertThat(update(TheBook, Audiobook)?.state).isNotNull()
        assertThat(keptData.manifest(TheBook)).isEqualTo(manifest)
        assertPlays(manifest, it)

        remover.release(TheBook)
        it.runCurrent()

        assertThat(record(TheBook, Audiobook).version).isEqualTo("newer")
        assertThat(keptData.manifest(TheBook)).isEqualTo(newerManifest)
        assertPlays(newerManifest, it)
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `a newer manifest that waited when the application stopped is put in place at the next start`() = keptTest {
        audiobookChanged()
        remover.hold(TheBook)
        askUpdate(TheBook)
        runner.runUntilIdle()
        val fresh = restartedAs(queueIn(it))

        fresh.runner.runUntilIdle()

        assertThat(keptData.manifest(TheBook)).isEqualTo(newerManifest)
        fresh.assertPlays(newerManifest, it)
    }

    @Test
    fun `an update taken back leaves no manifest waiting`() = keptTest {
        audiobookChanged()
        remover.hold(TheBook)
        askUpdate(TheBook)
        runner.runUntilIdle()

        queue.cancel(TheBook)

        assertThat(besideNames(TheBook)).isEmpty()
        assertThat(keptData.manifest(TheBook)).isEqualTo(manifest)
    }

    // --- narration

    /** Small tracks; the second version reads another file in the place of the second track. */
    private val playlist = Recorded.playlist("after").let { recorded ->
        recorded.copy(resources = recorded.resources.map { it.copy(sizeBytes = 10) })
    }
    private val newerPlaylist = playlist.copy(
        items = playlist.items.map { if (it.audioHref == OldTrack) it.copy(audioHref = NewTrack) else it },
        resources = playlist.resources.map { if (it.href == OldTrack) it.copy(href = NewTrack) else it },
    )

    private fun serveNarration(version: String, playlist: ReadAlongPlaylist) {
        serve(Books.book(Books.readAlong(31).copy(contentVersion = version), id = "1"))
        fixture.readAlong.playlistResult = ApiResult.Success(playlist)
    }

    /** The tracks of the kept map, in [version], each of which plays from the device. */
    private suspend fun QueueFixture.playingTracks(version: String, scope: TestScope): List<String> {
        val map = checkNotNull(keptData.map(TheBook, FileId("31")))
        assertThat(map.contentVersion).isEqualTo(version)
        val downloaded = downloaded(scope)
        val tracks = map.tracks.map { it.href }.distinct()
        tracks.forEach { href ->
            val url = checkNotNull(fixture.readAlong.trackUrl(TheBook, FileId("31"), href))
            assertThat(downloaded.audio(url)).isNotNull()
        }
        return tracks
    }

    @Test
    fun `the kept map is the one of the kept tracks until the newer ones take their place`() = keptTest {
        serveNarration("first", playlist)
        queue.ask(TheBook, setOf(Narration))
        runner.runUntilIdle()
        serveNarration("second", newerPlaylist)

        updates.checkBook(TheBook)

        assertThat(update(TheBook, Narration)?.version).isEqualTo("second")
        assertThat(playingTracks("first", it)).contains(OldTrack)

        remover.hold(TheBook)
        updates.update(TheBook)
        runner.runUntilIdle()

        assertThat(update(TheBook, Narration)?.state).isNotNull()
        assertThat(playingTracks("first", it)).contains(OldTrack)

        remover.release(TheBook)
        it.runCurrent()

        assertThat(record(TheBook, Narration).version).isEqualTo("second")
        assertThat(record(TheBook, Text).version).isEqualTo("second")
        assertThat(playingTracks("second", it)).contains(NewTrack)
        assertThat(besideNames(TheBook)).isEmpty()
    }

    // --- the page

    @Test
    fun `a page that names another file than the kept one waits for the swap, and keeps the reading`() = keptTest {
        val first = Books.book(Books.epub(11), id = "1")
        val second = Books.book(Books.epub(12), id = "1")
        serve(first)
        queue.ask(TheBook, setOf(Text))
        runner.runUntilIdle()
        keptData.keep(first, readingPercent = 40)
        serve(second)

        updates.checkBook(TheBook)

        assertThat(keptData.detail(TheBook)).isEqualTo(first)

        remover.hold(TheBook)
        updates.update(TheBook)
        runner.runUntilIdle()

        assertThat(keptData.detail(TheBook)).isEqualTo(first)
        assertThat(downloaded(it).text(TheBook, FileId("11"))).isNotNull()

        remover.release(TheBook)
        it.runCurrent()

        assertThat(keptData.detail(TheBook)).isEqualTo(second)
        assertThat(keptData.reading(TheBook)?.percent).isEqualTo(40)
        assertThat(downloaded(it).text(TheBook, FileId("12"))).isNotNull()
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `a check refreshes a page that still names the kept files`() = keptTest {
        val first = Books.book(Books.epub(11), id = "1")
        serve(first)
        queue.ask(TheBook, setOf(Text))
        runner.runUntilIdle()
        val renamed = first.copy(title = "Another Title")
        serve(renamed)

        updates.checkBook(TheBook)

        assertThat(keptData.detail(TheBook)).isEqualTo(renamed)
    }
}
