package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.store.FilePlaces
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often, at most, the registry is told how far a file is. */
private const val ProgressEveryMillis = 500L
private const val CopySuffix = ".copy"

private val PlannedFile.isPage: Boolean get() = kind == FileKind.Page

internal enum class StepResult {
    Done,

    /** The network: the format waits, and is tried again. */
    Cut,

    Failed,

    /** It is no longer wanted as it was asked: the run of its book ends there. */
    Stopped,
}

/** The files a record says are in their place, each with its version. */
internal typealias KnownFiles = Set<Pair<File, String?>>

/** Where the files of a format come from. */
internal class FormatSources(val plans: FormatPlans, val fetcher: FileFetcher, val caches: Set<CachedFiles>)

/**
 * The files one step of a format must have whole in their places, and how it says where it is.
 *
 * @property known the files a record says are in their place, each with its version: any other file found
 *   there is not trusted.
 * @property sizes what the device has of the files the step counts.
 * @property set writes where the step is, and how many of the pages wanted are in their place: none for
 *   files that are not pages.
 */
internal class Batch(
    val bookId: BookId,
    val format: DownloadFormat,
    val wanted: List<PlannedFile>,
    val known: KnownFiles,
    val sizes: FormatSizes,
    val set: suspend (FormatState, pages: Int) -> Unit,
)

/**
 * A batch on its way: [pages] of the pages it wants are in their place, and [sizes] is what the device has
 * of the files it counts.
 */
private class Going(val batch: Batch, var pages: Int) {
    val sizes = RunningSizes(batch.sizes)

    suspend fun set(state: FormatState) = batch.set(state, pages)
}

/**
 * Fetches the files of a [Batch] that are not whole in their places, and says how far it is: for a download
 * into the kept places, for an update into those beside them.
 *
 * @param places where the files go; the fetcher of [sources] writes there too.
 * @param io where the disk is read and written.
 * @param freeBytes the room left where the files go.
 */
internal class FileRun(
    private val places: FilePlaces,
    private val sources: FormatSources,
    private val io: CoroutineDispatcher,
    private val freeBytes: () -> Long = places::freeBytes,
) {
    /** How many files were put in place since the start. */
    var fetched = 0
        private set

    /** Says what a format is made of, for who fetches it here. */
    val plans: FormatPlans get() = sources.plans

    /**
     * [StepResult.Done] once every file of [batch] is whole in its place: one that already is, in the version
     * wanted, is not fetched again. The files are fetched one at a time, in their order. The pages in their
     * place, and the bytes on the device, are counted from what the device has when the run starts, then as
     * each file arrives: no file is looked at again for another.
     */
    suspend fun run(batch: Batch): StepResult {
        val todo = withContext(io) { missing(batch) }
        val going = Going(batch, pages = batch.wanted.count { it.isPage } - todo.count { it.isPage })
        val short = withContext(io) {
            going.sizes.count()
            left(going, todo) - freeBytes()
        }
        if (short > 0) return fail(going, DownloadFailure.NoSpace(short))
        tell(going, FormatState::Downloading)
        for (file in todo) {
            val result = fetch(going, file)
            withContext(io) { going.sizes.look(file) }
            when (result) {
                FetchResult.Done -> {
                    fetched++
                    if (file.isPage) going.pages++
                    tell(going, FormatState::Downloading)
                }
                is FetchResult.Cut -> {
                    tell(going) { received, total -> FormatState.Waiting(WaitReason.NoNetwork, received, total) }
                    return StepResult.Cut
                }
                is FetchResult.Refused -> return fail(going, result.failure)
            }
        }
        return StepResult.Done
    }

    /**
     * Before a record says the files [planned]: what is in their places and is not [known] in the version
     * planned goes. See [dropStale].
     */
    suspend fun dropStale(bookId: BookId, format: DownloadFormat, planned: List<PlannedFile>, known: KnownFiles) =
        withContext(io) { places.dropStale(bookId, format, planned, known) }

    /** The bytes the device has of the files [batch] counts. */
    suspend fun received(batch: Batch): Long = withContext(io) { batch.sizes.received() }

    /**
     * The files wanted that are not whole in their place. A file in place is whole when a record says it is of
     * the version wanted, and when it has the length planned; any other is removed.
     */
    private fun missing(batch: Batch): List<PlannedFile> = batch.wanted.filterNot { file ->
        val target = places.file(batch.bookId, batch.format, file.name)
        val length = file.expectedBytes ?: target.length()
        val whole = target.isFile && (target to file.version) in batch.known && length == target.length()
        if (!whole) target.delete()
        whole
    }

    /**
     * The bytes still to come: of the files whose size is known, and, for those that say none, what is left
     * of the estimate of them all.
     */
    private fun left(going: Going, todo: List<PlannedFile>): Long {
        val batch = going.batch
        val known = todo.sumOf { file ->
            val received = places.part(batch.bookId, batch.format, file.name).length()
            file.expectedBytes?.minus(received)?.coerceAtLeast(0) ?: 0
        }
        return known + if (todo.any { it.expectedBytes == null }) going.sizes.estimatedLeft() else 0
    }

    private suspend fun fetch(going: Going, file: PlannedFile): FetchResult = coroutineScope {
        val batch = going.batch
        if (withContext(io) { copied(batch, file) }) return@coroutineScope FetchResult.Done
        val told = MutableStateFlow<Long?>(null)
        val others = going.sizes.without(file)
        val total = going.sizes.total()
        val writer = launch {
            told.filterNotNull().collect { bytes ->
                val received = others?.plus(bytes)
                if (received != null) going.set(FormatState.Downloading(received, total?.coerceAtLeast(received)))
                delay(ProgressEveryMillis)
            }
        }
        try {
            sources.fetcher.fetch(batch.bookId, batch.format, file) { told.value = it }
        } finally {
            writer.cancel()
        }
    }

    /** Whether a cache had [file]: it is then in its place, and what was received of it is dropped. */
    private fun copied(batch: Batch, file: PlannedFile): Boolean {
        if (sources.caches.isEmpty()) return false
        val target = places.file(batch.bookId, batch.format, file.name)
        val copy = File(target.path + CopySuffix)
        val done = try {
            places.folders.make(target.parentFile) && sources.caches.any { it.copyOf(file, copy) } &&
                copy.renameTo(target)
        } catch (_: IOException) {
            false
        }
        copy.delete()
        if (done) PartFile(target, places.part(batch.bookId, batch.format, file.name)).discard()
        return done
    }

    /** Writes the state made of the bytes on the device and of the bytes expected. */
    private suspend fun tell(going: Going, state: (Long, Long?) -> FormatState) =
        going.set(state(going.sizes.received, going.sizes.total()))

    private suspend fun fail(going: Going, reason: DownloadFailure): StepResult {
        tell(going) { received, _ -> FormatState.Failed(reason, received) }
        return StepResult.Failed
    }
}
