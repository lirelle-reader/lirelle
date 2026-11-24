package app.tfdn.lirelle.source.bookorbit.library

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
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionNarrationDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionNarrationWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionTextWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionWritesDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingSessionWriteDto
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import app.tfdn.lirelle.source.bookorbit.network.fileIdOf
import app.tfdn.lirelle.source.bookorbit.network.readIdOf
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.ResponseBody

private const val CopyBlockBytes = 64 * 1024
private const val Superseded = "superseded"
private val Percentages = 0.0..100.0

/** The device could not take the bytes (no room left, for example): not the server's doing. */
private class SinkFailure(cause: IOException) : RuntimeException(cause)

/**
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 * @param io where the bytes of a download are copied.
 */
internal class DefaultReaderRepository(
    private val api: ApiProvider<ReaderApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReaderRepository {

    override fun fileUrl(fileId: FileId, audioless: Boolean): String? = serverUrl()?.let {
        "$it/api/v1/books/files/${fileId.bookOrbit()}/" + if (audioless) "audioless-epub/download" else "serve"
    }

    private suspend fun <T> call(block: suspend (ReaderApi) -> T): ApiResult<T> {
        val remote = serverUrl()?.let(api::forServer) ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { block(remote) }
    }

    override suspend fun position(fileId: FileId): ApiResult<ReadingPosition> = call { api ->
        val number = fileId.bookOrbit()
        api.positions(number).items.firstOrNull { it.fileId == number }?.let(::position)
            ?: ReadingPosition(fileId, cfi = null, percent = 0.0, updatedAt = null)
    }

    override suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant?,
        page: Int?,
    ): ApiResult<SavedPosition> = call { api ->
        val write = PositionWriteDto(
            fileId = fileId.bookOrbit(),
            percentage = percent.coerceIn(Percentages),
            text = PositionTextWriteDto(cfi, page),
            // An Instant prints as strict ISO-8601 in UTC, which is what the server checks.
            editedAt = editedAt?.toString(),
        )
        api.savePosition(write)
    }

    override suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant?,
    ): ApiResult<SavedPosition> = call { api ->
        val write = PositionWriteDto(
            fileId = fileId.bookOrbit(),
            percentage = percent.coerceIn(Percentages),
            kind = "narration",
            narration = PositionNarrationWriteDto(place.seconds, place.fragment, place.sectionIndex),
            editedAt = editedAt?.toString(),
        )
        // The narration's own time, which the server compares a late narration with; else the position's.
        api.savePosition(write) { progress -> instant(progress?.narration?.updatedAt) ?: positionStamp(progress) }
    }

    override suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> = call { api ->
        api.saveSession(
            fileId.bookOrbit(),
            ReadingSessionWriteDto(
                sessionId = session.sessionId,
                startedAt = session.startedAt.toString(),
                endedAt = session.endedAt.toString(),
                durationSeconds = session.activeSeconds.coerceAtLeast(0),
                progressDelta = session.progressDelta,
                endProgress = session.endPercent?.coerceIn(Percentages),
            ),
        )
    }

    /**
     * When it was last written is its `updatedAt`, a full time (the end, or the rating given just after);
     * failing that, the end of the day it ended on. The newest is the first the server lists: the server's
     * order (newest first) is kept; ids are not compared.
     */
    override suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?> = call { api ->
        api.readingAttempts(bookId.bookOrbit()).items.firstOrNull { it.outcome != null }?.let { attempt ->
            FinishedReading(
                id = readIdOf(attempt.id),
                lastChangedAt = instant(attempt.updatedAt) ?: attempt.endedOn?.let(::endOfDay),
                rating = attempt.rating?.roundToInt()?.takeIf { it in Stars },
            )
        }
    }

    override suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit> = call { api ->
        api.rate(readingId.bookOrbit(), buildJsonObject { put("rating", JsonPrimitive(rating)) })
    }

    override suspend fun nextVolume(seriesId: SeriesId, bookId: BookId, group: VolumeGroup): ApiResult<NextVolume?> =
        call { api ->
            api.nextVolume(seriesId.bookOrbit(), bookId.bookOrbit(), formatGroup(group)).next
                ?.let { NextVolume(bookIdOf(it.bookId), it.title, it.seriesIndex) }
        }

    override suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> = call { api ->
        val number = fileId.bookOrbit()
        val body = if (audioless) api.audiolessFile(number) else api.file(number)
        withContext(io) { body.use { copy(it, sink, onProgress) } }
    }
}

/** The date is that of the text position; rows older than that field only have the general one. */
private fun position(dto: PositionDto) = ReadingPosition(
    fileId = fileIdOf(dto.fileId),
    cfi = dto.text?.cfi,
    percent = dto.percentage,
    updatedAt = instant(dto.text?.updatedAt) ?: instant(dto.updatedAt),
    narration = dto.narration?.let(::narrationPlace),
    narrationUpdatedAt = instant(dto.narration?.updatedAt),
    page = dto.text?.page,
)

/** The server's name for the formats of a group: `cbx` is CBZ, CBR and CB7 together. */
private fun formatGroup(group: VolumeGroup): String = when (group) {
    VolumeGroup.Epub -> "epub"
    VolumeGroup.Comics -> "cbx"
}

/** The place is only known when the server holds both the sentence and its section. */
private fun narrationPlace(dto: PositionNarrationDto): NarrationPlace? {
    val fragment = dto.fragment ?: return null
    val section = dto.sectionIndex ?: return null
    return NarrationPlace(dto.seconds ?: 0.0, fragment, section)
}

/** Sends one write and maps its result, whatever its kind; [stamp] reads when the server stored it. */
private suspend fun ReaderApi.savePosition(
    write: PositionWriteDto,
    stamp: (PositionDto?) -> Instant? = ::positionStamp,
): SavedPosition {
    val result = savePositions(PositionWritesDto(listOf(write))).results.firstOrNull { it.fileId == write.fileId }
    return SavedPosition(
        outcome = if (result?.status == Superseded) SaveOutcome.Superseded else SaveOutcome.Saved,
        storedAt = stamp(result?.progress),
    )
}

/** The later of the two dates the server keeps of a text position: whichever it compares a late write with. */
private fun positionStamp(progress: PositionDto?): Instant? =
    listOfNotNull(instant(progress?.updatedAt), instant(progress?.text?.updatedAt)).maxOrNull()

/** Writes [count] bytes, or flushes when there is none left. */
private fun OutputStream.writeBlock(block: ByteArray, count: Int) = try {
    if (count > 0) write(block, 0, count) else flush()
} catch (failure: IOException) {
    throw SinkFailure(failure)
}

private val Stars = 1..5

/** The end of [day] (an ISO date) in UTC, or null when it does not read so. */
private fun endOfDay(day: String): Instant? =
    runCatching { LocalDate.parse(day).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

/** One block at a time, so that memory use does not depend on the size of the book. */
private suspend fun copy(body: ResponseBody, sink: OutputStream, onProgress: (DownloadProgress) -> Unit) {
    val total = body.contentLength().takeIf { it >= 0 }
    val source = body.byteStream()
    val block = ByteArray(CopyBlockBytes)
    var copied = 0L
    onProgress(DownloadProgress(copied, total))
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = source.read(block)
        if (read < 0) break
        sink.writeBlock(block, read)
        copied += read
        onProgress(DownloadProgress(copied, total))
    }
    sink.writeBlock(block, 0)
}
