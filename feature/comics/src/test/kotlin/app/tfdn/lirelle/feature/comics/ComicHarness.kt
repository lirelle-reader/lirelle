package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionSyncFactory
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import app.tfdn.lirelle.core.testing.FakeComicPages
import app.tfdn.lirelle.core.testing.FakeComicPagesOpener
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.feature.comics.end.EndOfAlbumServices
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** The album of the tests: the book 14, its CBZ file 31, 48 pages. */
internal val AlbumBook = BookId("14")
internal val AlbumFile = FileId("31")
internal const val Pages = 48

private const val BootMillis = 5_000_000L

/** A monotonic clock and a wall clock moved by hand, together. */
internal class TestClocks(private val start: Instant = Instant.parse("2026-11-17T10:00:00Z")) {
    private var elapsedMillis = 0L

    val monotonic = MonotonicClock { BootMillis + elapsedMillis }

    val wall: Clock = object : Clock() {
        override fun instant(): Instant = start.plusMillis(elapsedMillis)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    val now: Instant get() = wall.instant()

    fun advance(by: Duration) {
        elapsedMillis += by.inWholeMilliseconds
    }
}

/**
 * A comics reader over fakes. The server holds no position and cannot be reached for one until [serverHas];
 * what is written on leaving runs at once. Its device was already told what reading panel by panel is, unless
 * the test gives settings that say otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ComicHarness(
    scope: TestScope,
    detail: BookDetail = sampleComicDetail(),
    val settings: ComicSettingsStore = InMemoryComicSettingsStore(noticeSeen = true),
    capabilities: SourceCapabilities = SourceCapabilities.Everything,
) {
    val clocks = TestClocks()
    val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(detail) }

    /** What the downloads keep: the pages of the books, their formats, and the deletions asked of them. */
    val kept = FakeKeptBookData()
    val downloads = FakeDownloads()
    val deletions = FakeClosingDeletions(downloads)
    val repository = FakeReaderRepository()
    val changes = ReadingChanges()
    val store = InMemoryPositionStore()
    val outbox = InMemoryOutbox()
    val pages = FakeComicPages(PageCountResult.Known(Pages))
    val opener = FakeComicPagesOpener(pages)

    /** The zones of the album's pages, and what the reader told them. */
    val panels = FakePagePanels()
    val panelsOpener = FakePagePanelsOpener(panels)
    val timings = ComicTimings()

    /** Whether the device has a network: the tests take it away and give it back. */
    val online = MutableStateFlow(true)

    private val flusher = OutboxFlusher(outbox, repository, scope.backgroundScope, clocks.wall, ReadingChanges())
    private val services = ComicReaderServices(
        books = ComicBooks(library, kept, downloads),
        end = EndOfAlbumServices(repository, changes, DeviceCopySources(downloads, deletions), capabilities),
        openers = ComicOpeners(opener, panelsOpener),
        positions = PositionSyncFactory(repository, store, outbox, flusher, clocks.wall),
        clocks = SessionClockFactory(clocks.monotonic, clocks.wall),
        outbox = outbox,
        flusher = flusher,
        settings = settings,
        timings = timings,
    )
    private val lasting =
        CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler))

    fun reader(fromStart: Boolean = false) =
        ComicReaderViewModel(AlbumBook.value, fromStart, services, ComicNetwork(online), lasting)

    /** The server holds the page numbered [page] (from 1), written [secondsFromStart] after the test began. */
    fun serverHas(page: Int?, percent: Double = percentAt(page), secondsFromStart: Long = 0) {
        val at = clocks.now.plusSeconds(secondsFromStart)
        val position = ReadingPosition(AlbumFile, null, percent, updatedAt = at, page = page)
        repository.positionResult = ApiResult.Success(position)
    }

    /** The device holds the page numbered [page], never sent, written [secondsFromStart] after the test began. */
    suspend fun deviceHas(page: Int, secondsFromStart: Long) {
        val position = LocalPosition(AlbumFile, null, percentAt(page), clocks.now.plusSeconds(secondsFromStart), page)
        store.put(position)
        outbox.enqueuePosition(position)
    }

    private fun percentAt(page: Int?): Double = (page ?: 0) * 100.0 / Pages
}
