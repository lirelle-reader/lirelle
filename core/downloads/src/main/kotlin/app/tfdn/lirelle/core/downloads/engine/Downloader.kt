package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.FilePlaces
import app.tfdn.lirelle.core.network.ApiError
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response

private const val BlockBytes = 64 * 1024

/** The room the device keeps whatever is downloaded: a download stops rather than fill it. */
private const val FreeFloorBytes = 16L * 1024 * 1024

sealed interface FetchResult {
    data object Done : FetchResult

    /** The network: the same request can go on later. [receivedBytes] are those kept of the file. */
    data class Cut(val receivedBytes: Long, val error: ApiError) : FetchResult

    /** It will not work again as it is. */
    data class Refused(val failure: DownloadFailure) : FetchResult
}

/** Fetches one file of a book into its place. */
fun interface FileFetcher {
    /**
     * [FetchResult.Done] once the file is whole in its place. [onProgress] is told the bytes of the file on the
     * device as they come. A cancelled fetch keeps what it received.
     */
    suspend fun fetch(
        bookId: BookId,
        format: DownloadFormat,
        file: PlannedFile,
        onProgress: (receivedBytes: Long) -> Unit,
    ): FetchResult
}

/**
 * Fetches the files of every format, one at a time and each whole: into a `.part` beside its place,
 * moved there in one step once complete, so that a file in place is always a whole file.
 *
 * @param client the authenticated client, patient with a book's files.
 * @param files where the files go: the kept places, or those of an update beside them.
 * @param locations where the server serves each file now, asked as it is fetched: the plan names none.
 * @param refusals says what a refusal of the server is: the profile's source reads it.
 * @param io where the network is read and the files are written.
 * @param freeBytes the room left where the downloads go.
 * @param now the time in milliseconds, for how often progress is told.
 */
class Downloader(
    private val client: OkHttpClient,
    private val files: FilePlaces,
    private val locations: DownloadLocations,
    private val refusals: DownloadRefusals,
    private val io: CoroutineDispatcher,
    private val freeBytes: () -> Long = files::freeBytes,
    private val now: () -> Long = System::currentTimeMillis,
) : FileFetcher {
    /**
     * Fetches [file] into its place for [bookId]/[format]; resumes from its `.part` when it can.
     * A cancelled fetch keeps what it received. [onProgress] is told the bytes of the file on the
     * device: when the body starts, then at most every 250 ms, and when the body ends.
     */
    override suspend fun fetch(
        bookId: BookId,
        format: DownloadFormat,
        file: PlannedFile,
        onProgress: (receivedBytes: Long) -> Unit,
    ): FetchResult = withContext(io) {
        val place = PartFile(files.file(bookId, format, file.name), files.part(bookId, format, file.name))
        try {
            val from = place.resumableBytes(file)
            // All of it came, and the download stopped before it was put in place.
            val whole = from > 0 && from == file.expectedBytes
            val url = locations.url(bookId, file.source)
            when {
                whole -> finish(place, file, from)
                // No address: no server to ask (no session), or none for a file of that kind. The file waits as
                // for a session that ended.
                url == null -> FetchResult.Cut(place.part.length(), ApiError.SessionExpired)
                else -> request(place, url, file, from, onProgress)
            }
        } catch (failure: IOException) {
            // A cancelled call fails as a lost connection does: it is the cancellation that is told.
            currentCoroutineContext().ensureActive()
            failed(failure, place, file)
        }
    }

    private suspend fun request(
        place: PartFile,
        url: String,
        file: PlannedFile,
        from: Long,
        onProgress: (Long) -> Unit,
    ): FetchResult {
        val call = client.newCall(rangedRequest(url, from))
        return call.cancelledWithCaller {
            call.execute().use { response ->
                val start = response.takeIf { it.isSuccessful }?.bodyStart(from)
                when {
                    start != null -> receive(response, start, place, file, onProgress)
                    response.isSuccessful || (from > 0 && response.refusesRange) -> {
                        // Not the rest of the file as the device has it: the next try starts it again.
                        place.discard()
                        FetchResult.Cut(0, ApiError.Unexpected)
                    }
                    else -> refused(response)
                }
            }
        }
    }

    private fun refused(response: Response): FetchResult = FetchResult.Refused(refusals.failure(response))

    private suspend fun receive(
        response: Response,
        start: BodyStart,
        place: PartFile,
        file: PlannedFile,
        onProgress: (Long) -> Unit,
    ): FetchResult {
        if (start.from == 0L) place.begin(file.version, files.folders)
        val total = start.totalBytes ?: file.expectedBytes
        val progress = Progress(now, onProgress)
        var received = start.from
        progress.received(received)
        val full = FileOutputStream(place.part, true).use { out ->
            copy(response.body.byteStream(), out, hasRoom = { freeBytes() >= FreeFloorBytes }) {
                received += it
                progress.received(received)
            }
        }
        if (full) return FetchResult.Refused(noSpace(total?.minus(received)))
        onProgress(received)
        return finish(place, file, total)
    }

    /**
     * One block at a time, as long as the device has room. Returns whether it stopped for want of room.
     */
    private suspend fun copy(
        source: InputStream,
        out: FileOutputStream,
        hasRoom: () -> Boolean,
        onBlock: (Int) -> Unit,
    ): Boolean {
        val block = ByteArray(BlockBytes)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = source.read(block)
            if (read < 0) return false
            if (!hasRoom()) return true
            try {
                out.write(block, 0, read)
            } catch (failure: IOException) {
                throw WriteFailed(failure)
            }
            onBlock(read)
        }
    }

    /**
     * The part becomes the file when it has the length the server said, or else the one the plan
     * expects, and when it is what its kind says: an EPUB, a PDF.
     */
    private fun finish(place: PartFile, file: PlannedFile, totalBytes: Long?): FetchResult {
        val length = place.part.length()
        if (totalBytes != null && totalBytes != length) {
            // More than the file: nothing to go on from.
            if (length > totalBytes) place.discard()
            return FetchResult.Cut(place.part.length(), ApiError.Unexpected)
        }
        if (!file.kind.isKindOf(place.part)) {
            place.discard()
            return FetchResult.Refused(DownloadFailure.NotAnEpub)
        }
        place.complete()
        return FetchResult.Done
    }

    /** What was received stays, for the next try. */
    private fun failed(failure: IOException, place: PartFile, file: PlannedFile): FetchResult {
        val received = place.part.length()
        return when {
            failure.saysNoSpace() -> FetchResult.Refused(noSpace(file.expectedBytes?.minus(received)))
            // The device's side: a directory removed under the download, a file that cannot be written.
            failure is WriteFailed -> FetchResult.Cut(received, ApiError.Unexpected)
            // The network's: the server was not reached, or its answer did not come whole.
            else -> FetchResult.Cut(received, ApiError.ServerUnreachable)
        }
    }

    /** How much room is missing for [leftBytes] still to come, the room the device keeps included. */
    private fun noSpace(leftBytes: Long?): DownloadFailure.NoSpace {
        val missing = (leftBytes ?: 0).coerceAtLeast(0) + FreeFloorBytes - freeBytes()
        return DownloadFailure.NoSpace(missing.coerceAtLeast(1))
    }
}
