package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [ReaderRepository]. Set the result fields, then read what was asked.
 * [gate], when set, holds every suspending call until it is completed.
 */
@Suppress("TooManyFunctions") // One function per repository operation.
class FakeReaderRepository : ReaderRepository {
    var positionResult: ApiResult<ReadingPosition> = ApiResult.Failure(ApiError.ServerUnreachable)
    var savePositionResult: ApiResult<SavedPosition> = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
    var saveSessionResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var finishedReadingResult: ApiResult<ReadId?> = ApiResult.Success(null)

    /** What [finishedReading] says of a reading beyond its id: when it was last written, its rating. */
    var readingDetails: Map<ReadId, FinishedReading> = emptyMap()
    var rateResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var nextVolumeResult: ApiResult<NextVolume?> = ApiResult.Success(null)

    /** What a download writes to its sink. */
    var fileContent: ByteArray = ByteArray(0)

    /** A failing download writes the first half of [fileContent], as an interrupted one would. */
    var downloadResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /**
     * When set, a download writes the first half of [fileContent], then waits here: the moment
     * to look at what is on disk, or to cancel.
     */
    var downloadGate: CompletableDeferred<Unit>? = null
    var gate: CompletableDeferred<Unit>? = null
    /** The server of the profile, without a trailing slash; null once signed out. */
    var server: String? = "https://books.example"

    /**
     * Where a source of the tests serves a file on [server], under no server's routes: `<server>/files/<fileId>`,
     * and `/audioless` after it for the read-along EPUB without its audio.
     */
    override fun fileUrl(fileId: FileId, audioless: Boolean): String? =
        server?.let { "$it/files/$fileId" + if (audioless) "/audioless" else "" }

    val calls = mutableListOf<String>()
    val savedPositions = mutableListOf<ReadingPosition>()

    /** The `editedAt` given with each saved position, in the order of [savedPositions]. */
    val savedEditedAts = mutableListOf<Instant?>()
    var saveNarrationResult: ApiResult<SavedPosition> = ApiResult.Success(SavedPosition(SaveOutcome.Saved))

    /** The file and place of each narration written, and the `editedAt` given with it, in the same order. */
    val savedNarrations = mutableListOf<Pair<FileId, NarrationPlace>>()
    val savedNarrationEditedAts = mutableListOf<Instant?>()
    val savedSessions = mutableListOf<Pair<FileId, ReadingSessionReport>>()

    /** Among which files each next volume was asked, in the order of the calls. */
    val nextVolumeGroups = mutableListOf<VolumeGroup>()

    /** File id and whether the audio-less variant was asked, per download. */
    val downloads = mutableListOf<Pair<FileId, Boolean>>()

    private suspend fun <T> answer(call: String, result: T): T {
        calls += call
        gate?.await()
        return result
    }

    override suspend fun position(fileId: FileId) = answer("position $fileId", positionResult)

    override suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant?,
        page: Int?,
    ): ApiResult<SavedPosition> {
        savedEditedAts += editedAt
        savedPositions += ReadingPosition(fileId, cfi, percent, updatedAt = null, page = page)
        return answer("savePosition $fileId", savePositionResult)
    }

    override suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant?,
    ): ApiResult<SavedPosition> {
        savedNarrations += fileId to place
        savedNarrationEditedAts += editedAt
        return answer("saveNarration $fileId", saveNarrationResult)
    }

    override suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> {
        savedSessions += fileId to session
        return answer("saveSession $fileId ${session.sessionId}", saveSessionResult)
    }

    override suspend fun finishedReadingId(bookId: BookId) = answer("finishedReadingId $bookId", finishedReadingResult)

    override suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?> {
        val id = answer("finishedReading $bookId", finishedReadingResult)
        return when (id) {
            is ApiResult.Failure -> id
            is ApiResult.Success ->
                ApiResult.Success(id.value?.let { readingDetails[it] ?: FinishedReading(it, null, null) })
        }
    }

    override suspend fun rate(readingId: ReadId, rating: Int?) = answer("rate $readingId $rating", rateResult)

    override suspend fun nextVolume(seriesId: SeriesId, bookId: BookId, group: VolumeGroup): ApiResult<NextVolume?> {
        nextVolumeGroups += group
        return answer("nextVolume $seriesId $bookId", nextVolumeResult)
    }

    override suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> {
        downloads += fileId to audioless
        val total = fileContent.size.toLong()
        val half = fileContent.size / 2
        onProgress(DownloadProgress(0, total))
        sink.write(fileContent, 0, half)
        sink.flush()
        onProgress(DownloadProgress(half.toLong(), total))
        downloadGate?.await()
        val result = answer("download $fileId", downloadResult)
        if (result is ApiResult.Success) {
            sink.write(fileContent, half, fileContent.size - half)
            sink.flush()
            onProgress(DownloadProgress(total, total))
        }
        return result
    }
}
