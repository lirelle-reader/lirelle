package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptReading
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.FilePlaces
import app.tfdn.lirelle.core.downloads.update.CheckClock
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.downloads.update.RegistryDownloadUpdates
import app.tfdn.lirelle.core.downloads.update.UpdateCancel
import app.tfdn.lirelle.core.downloads.update.UpdateCheck
import app.tfdn.lirelle.core.downloads.update.UpdateInputs
import app.tfdn.lirelle.core.downloads.update.UpdateRun
import app.tfdn.lirelle.core.downloads.update.UpdateStates
import app.tfdn.lirelle.core.downloads.update.UpdateSwap
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal val Wifi = NetworkState(online = true, unmetered = true)
internal val Mobile = NetworkState(online = true, unmetered = false)
internal val NoNetwork = NetworkState(online = false, unmetered = false)

/**
 * The registry in memory; [states] are those each write left, in order.
 *
 * @property afterWrite told what each write left of its book, once it is written: a test ends a process there.
 */
internal class MemoryRegistry : DownloadRegistry {
    private val stored = MutableStateFlow(emptyMap<BookId, DownloadedBook>())
    val states = mutableListOf<Pair<DownloadFormat, FormatState>>()
    var afterWrite: (DownloadedBook?) -> Unit = {}

    override val books = stored.map { it.values.toList() }

    override suspend fun book(bookId: BookId): DownloadedBook? = stored.value[bookId]

    override suspend fun update(bookId: BookId, change: (DownloadedBook?) -> DownloadedBook?) {
        stored.update { books ->
            val changed = change(books[bookId])?.copy(bookId = bookId)
            changed?.formats?.forEach { states += it.format to it.state }
            if (changed == null) books - bookId else books + (bookId to changed)
        }
        afterWrite(stored.value[bookId])
    }

    override suspend fun clear() {
        stored.value = emptyMap()
    }
}

/** Puts each file in its place, whole, unless [on] says what happens to it. */
internal class FakeFetcher(private val files: FilePlaces) : FileFetcher {
    /** `"<bookId>/<format>/<name>"` of each fetch, in order. */
    val fetched = mutableListOf<String>()
    private val scripts = mutableMapOf<String, ArrayDeque<suspend FakeFetcher.(Fetch) -> FetchResult>>()

    class Fetch(val bookId: BookId, val format: DownloadFormat, val file: PlannedFile, val onProgress: (Long) -> Unit)

    /** What the next fetch of the file called [name] does, once; then the next [on], or the file is put in place. */
    fun on(name: String, fetch: suspend FakeFetcher.(Fetch) -> FetchResult) {
        scripts.getOrPut(name) { ArrayDeque() } += fetch
    }

    fun store(fetch: Fetch): FetchResult {
        val bytes = fetch.file.expectedBytes ?: UnknownSize
        files.file(fetch.bookId, fetch.format, fetch.file.name)
            .apply { checkNotNull(parentFile).mkdirs() }
            .writeBytes(ByteArray(bytes.toInt()))
        fetch.onProgress(bytes)
        return FetchResult.Done
    }

    override suspend fun fetch(
        bookId: BookId,
        format: DownloadFormat,
        file: PlannedFile,
        onProgress: (receivedBytes: Long) -> Unit,
    ): FetchResult {
        fetched += "$bookId/$format/${file.name}"
        val fetch = Fetch(bookId, format, file, onProgress)
        return scripts[file.name]?.removeFirstOrNull()?.invoke(this, fetch) ?: store(fetch)
    }

    companion object {
        /** The length of a file whose size the plan does not know. */
        const val UnknownSize = 7L
    }
}

internal class FakePlans : FormatPlans {
    val answers = mutableMapOf<Pair<BookId, DownloadFormat>, ApiResult<FormatPlan>>()
    val asked = mutableListOf<Pair<BookId, DownloadFormat>>()

    /** What each plan was asked for, in order. */
    val purposes = mutableListOf<PlanFor>()

    override suspend fun plan(bookId: BookId, format: DownloadFormat, purpose: PlanFor): ApiResult<FormatPlan> {
        asked += bookId to format
        purposes += purpose
        return answers[bookId to format] ?: ApiResult.Failure(ApiError.NotFound)
    }
}

/** Only says what was removed: `null` for the whole book. */
internal class FakeKept : KeptBookData {
    val removed = mutableListOf<Pair<BookId, DownloadFormat?>>()

    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) = Unit

    override suspend fun detail(bookId: BookId): BookDetail? = null

    override suspend fun reading(bookId: BookId): KeptReading? = null

    override fun coverFile(bookId: BookId): File? = null

    override suspend fun keepManifest(manifest: AudioManifest) = Unit

    override suspend fun manifest(bookId: BookId): AudioManifest? = null

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = true

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? = null

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) {
        removed += bookId to format
    }
}

/** No full pass is remembered. */
internal object NoClock : CheckClock {
    override suspend fun last(): Instant? = null

    override suspend fun mark(at: Instant) = Unit
}

/**
 * The queue, its removals, its updates and its runner over a fake server, a fake network and the test's clock.
 *
 * @param server what says what the formats are made of instead of [plans], given the registry and the files.
 * @param fetch what fetches the files of a download instead of [fetcher], given where they go.
 * @param updateFetch what fetches the files of an update instead of [updateFetcher], given where they go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class QueueFixture(
    private val scope: TestScope,
    root: File,
    server: ((DownloadRegistry, DownloadFiles) -> FormatPlans)? = null,
    fetch: ((FilePlaces, CoroutineDispatcher) -> FileFetcher)? = null,
    updateFetch: ((FilePlaces, CoroutineDispatcher) -> FileFetcher)? = null,
) {
    private val io = StandardTestDispatcher(scope.testScheduler)
    val registry = MemoryRegistry()
    val files = DownloadFiles(root)
    val fetcher = FakeFetcher(files)

    /** Puts the files of an update beside the kept ones. */
    val updateFetcher = FakeFetcher(files.updates)
    val plans = FakePlans()
    private val planner = server?.invoke(registry, files) ?: plans
    val kept = FakeKept()
    val network = MutableStateFlow(Wifi)
    val allowMetered = MutableStateFlow(false)
    val canDownload = MutableStateFlow(true)
    val caches = mutableSetOf<CachedFiles>()
    var freeBytes = Long.MAX_VALUE

    /** Where a server no test reaches serves the files: what the reader and the player look for. */
    val locations = testLocations("https://books.example")

    private val inputs = QueueInputs(network, allowMetered, canDownload)
    private val disposal = Disposal(registry, files, kept, io)
    val remover = DownloadRemover(registry, disposal, scope.backgroundScope)

    /** Told before the swap: nothing is swapped into a book that goes as it closes. */
    private val flagged =
        FlaggedBooks(registry, disposal, remover::unlessHeld).also { remover.whenReleased(it::delete) }
    private val swap = UpdateSwap(registry, files, remover::unlessHeld, io).also { remover.whenReleased(it::settle) }
    val queue = DownloadQueue(registry, disposal, UpdateCancel(registry, swap), canDownload) { now() }
    val closing: ClosingDeletions = ClosingDeletion(registry, flagged) { queue.cancel(it) }
    val updates: DownloadUpdates = RegistryDownloadUpdates(
        registry, UpdateCheck(registry, planner), NoClock, UpdateInputs({ true }, canDownload),
    ) { now() }
    private val states = RecordStates(registry) { now() }
    private val sources = FormatSources(planner, fetch?.invoke(files, io) ?: fetcher, caches)
    private val formatRun = FormatRun(states, files, sources, io) { freeBytes }
    private val updateSources = FormatSources(planner, updateFetch?.invoke(files.updates, io) ?: updateFetcher, caches)
    private val updateRun = UpdateRun(
        UpdateStates(registry), files, FileRun(files.updates, updateSources, io) { freeBytes }, inputs, swap,
    )
    private val start = StartCheck(registry, files, remover, flagged, swap, io)
    val runner = DownloadRunner(registry, inputs, formatRun, updateRun, queue, start, disposal)
        .also { runner -> remover.haltWith { block -> runner.halted(block) } }

    fun now(): Instant = Instant.ofEpochMilli(scope.testScheduler.currentTime)

    /** As the application does: the runner goes on for as long as the test. */
    fun keepRunning(): Job = scope.backgroundScope.launch { runner.run() }

    /** An audiobook's file, the asset of its [name]. */
    fun audio(name: String, bytes: Long? = 100, version: String? = "v1") =
        PlannedFile(name, FileSource.AudioAsset(name), version, bytes, resumable = true, kind = FileKind.Audio)

    /**
     * A text's EPUB, of the file [fileId]: by default the file whose id the [name] is made of, as the planner
     * names a text (`"12.epub"` is the file `12`'s).
     */
    fun epub(
        name: String = "12.epub",
        bytes: Long? = 40,
        version: String? = "v1",
        fileId: FileId = FileId(name.substringBefore('.')),
    ) = PlannedFile(
        name,
        FileSource.Whole(fileId),
        version,
        bytes,
        resumable = true,
        kind = FileKind.Epub,
    )

    fun serve(bookId: BookId, format: DownloadFormat, vararg planned: PlannedFile, version: String? = "v1") {
        val plan = FormatPlan(fileId = FileId("12"), version, planned.toList())
        plans.answers[bookId to format] = ApiResult.Success(plan)
    }

    /** A book with an EPUB and one audiobook file. */
    fun serveBook(bookId: BookId) {
        serve(bookId, DownloadFormat.Text, epub("$bookId.epub"))
        serve(bookId, DownloadFormat.Audiobook, audio("a$bookId"))
    }

    suspend fun state(bookId: BookId, format: DownloadFormat): FormatState? =
        registry.book(bookId)?.formats?.firstOrNull { it.format == format }?.state

    fun file(bookId: BookId, format: DownloadFormat, name: String): File = files.file(bookId, format, name)
}
