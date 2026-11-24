package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionSyncFactory
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeMarks
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import app.tfdn.lirelle.core.testing.InMemoryReaderSettingsStore
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.feature.reader.bridge.FakeReaderEngine
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.PageSettings
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import app.tfdn.lirelle.feature.reader.narration.FakeNarrationPlayer
import app.tfdn.lirelle.feature.reader.narration.FakeReadAlongMapStore
import app.tfdn.lirelle.feature.reader.narration.MemoryNarrationStore
import app.tfdn.lirelle.feature.reader.narration.NarrationServices
import app.tfdn.lirelle.feature.reader.text.FakeTextActions
import app.tfdn.lirelle.feature.reader.web.testArchive
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

const val StartCfi = "epubcfi(/6/4!/4/2)"
const val SecondCfi = "epubcfi(/6/4!/4/10)"
const val ThirdCfi = "epubcfi(/6/6!/4/2)"

val SampleToc = listOf(
    TocEntry(id = 0, label = "One", href = "one.xhtml", depth = 0),
    TocEntry(id = 1, label = "Two", href = "two.xhtml", depth = 0),
    TocEntry(id = 2, label = "Three", href = "three.xhtml", depth = 1),
)

fun relocated(
    cfi: String = StartCfi,
    fraction: Double = 0.1,
    moved: Boolean = true,
    atEnd: Boolean = false,
    tocId: Int? = 0,
) = PageMessage.Relocated(
    cfi = cfi,
    fraction = fraction,
    sectionIndex = 0,
    sectionCount = 3,
    page = 2,
    pages = 9,
    tocId = tocId,
    tocLabel = SampleToc.firstOrNull { it.id == tocId }?.label,
    atEnd = atEnd,
    moved = moved,
)

/**
 * A cache that plays [states], waiting at [gate] before the last one, and notes what is asked, held
 * and given back. As the real one, it holds nothing by itself once a collection has ended.
 */
class FakeReaderCache : ReaderCache {
    var states: List<CacheState> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    val opened = mutableListOf<Pair<CachedBookKey, Boolean>>()

    /** The book given with each opening. */
    val books = mutableListOf<BookId>()
    val held = mutableListOf<CachedBookKey>()
    val released = mutableListOf<CachedBookKey>()
    val discarded = mutableListOf<CachedBookKey>()

    /** What is held and not given back yet. */
    val stillHeld: List<CachedBookKey> get() = held - released

    override fun open(bookId: BookId, key: CachedBookKey, audioless: Boolean): Flow<CacheState> = flow {
        books += bookId
        opened += key to audioless
        states.forEachIndexed { index, state ->
            if (index == states.lastIndex) gate?.await()
            emit(state)
        }
    }

    override fun hold(key: CachedBookKey) {
        held += key
    }

    override fun release(key: CachedBookKey) {
        released += key
    }

    override suspend fun discard(key: CachedBookKey) {
        discarded += key
    }

    override fun cached(key: CachedBookKey): File? = null
}

/** A monotonic clock and a wall clock moved by hand, together. */
class HandClock(private val start: Instant = Instant.parse("2026-11-14T10:00:00Z")) : Clock() {
    private var elapsedMillis = 0L

    val monotonic = MonotonicClock { elapsedMillis }

    fun advance(by: Duration) {
        elapsedMillis += by.inWholeMilliseconds
    }

    override fun instant(): Instant = start.plusMillis(elapsedMillis)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** Everything a reader needs, faked: the test sets the fields, makes the view model, then plays the page. */
class ReaderHarness(private val scope: TestScope, folder: File) {
    val bookFile = File(folder, "book.epub")
    val archive = testArchive(bookFile, "a.txt" to "a")

    /** What the page reads the book from when it is the EPUB above. */
    val content = BookContent.Epub(archive)
    var detail: BookDetail = sampleBookDetail(id = "12")
    val library = FakeLibraryRepository()
    val repository = FakeReaderRepository()
    val outbox = InMemoryOutbox()
    val positions = InMemoryPositionStore()
    val settings = InMemoryReaderSettingsStore()
    val cache = FakeReaderCache().apply { states = listOf(CacheState.Ready(bookFile)) }
    val clock = HandClock()
    val changes = ReadingChanges()
    var webViewVersion: Int? = 130
    var epub: EpubOpening = EpubOpening.Opened(archive)
    val engine = FakeReaderEngine(bookBase = "/book/first/")
    val key = CachedBookKey(fileId = FileId("1"), contentVersion = null)
    val marks = FakeMarks()
    val textActions = FakeTextActions()
    val player = FakeNarrationPlayer()
    val places = MemoryNarrationStore()
    val maps = FakeReadAlongMapStore()
    val kept = FakeKeptBookData()
    val downloads = FakeDownloads()
    val deletions = FakeClosingDeletions(downloads)

    /** What the profile's source can do: everything, as BookOrbit, unless a test says otherwise. */
    var capabilities: SourceCapabilities = SourceCapabilities.Everything

    /** What the server says of the book when the reader opens; null for [detail]. */
    var bookAnswer: ApiResult<BookDetail>? = null

    private val flusher = OutboxFlusher(outbox, repository, scope.backgroundScope, clock, changes)
    private val sessions = ReadingSessionFactory(
        positions = PositionSyncFactory(repository, positions, outbox, flusher, clock),
        clocks = SessionClockFactory(clock.monotonic, clock),
        outbox = outbox,
        flusher = flusher,
        lasting = scope.backgroundScope,
    )

    val pageSettings: PageSettings
        get() = settings.current.toPageSettings(
            settings.current.theme.colours(systemDark = false),
            twoPages = false,
            noteLabels = EnglishNoteLabels,
        )

    /** With [fromStart], a reader asked to open the book at its beginning; with [narrate], to start its narration. */
    fun viewModel(fromStart: Boolean = false, narrate: Boolean = false): ReaderViewModel {
        library.bookResult = bookAnswer ?: ApiResult.Success(detail)
        val opener = BookOpener(library, cache, { epub }, { webViewVersion }, kept, downloads)
        val narration = NarrationServices(player, maps, places, kept, downloads)
        val device = DeviceCopySources(downloads, deletions)
        val services =
            ReaderServices(opener, sessions, repository, settings, changes, marks, textActions, narration, device)
        return ReaderViewModel("12", fromStart, narrate, services, scope.backgroundScope, capabilities)
    }

    /** A reader whose page shows the book, at [StartCfi]. */
    fun reading(dir: PageDirection = PageDirection.Ltr, sectionLabels: List<String?> = emptyList()): ReaderViewModel =
        opened(viewModel(), dir, fraction = 0.1, sectionLabels)

    /** A reader asked to start the book's narration, whose page shows the book at [StartCfi]. */
    fun narrating(): ReaderViewModel = opened(viewModel(narrate = true), PageDirection.Ltr, fraction = 0.1)

    /** A reader asked to open the book at its beginning, whose page shows it there. */
    fun readingAgain(): ReaderViewModel = opened(viewModel(fromStart = true), PageDirection.Ltr, fraction = 0.0)

    private fun opened(
        viewModel: ReaderViewModel,
        dir: PageDirection,
        fraction: Double,
        sectionLabels: List<String?> = emptyList(),
    ): ReaderViewModel {
        viewModel.onEngine(engine)
        engine.emit(PageMessage.Ready)
        engine.emit(PageMessage.Opened(SampleToc, dir, fixedLayout = false, title = null, sectionLabels))
        engine.emit(relocated(fraction = fraction, moved = false))
        return viewModel
    }
}
