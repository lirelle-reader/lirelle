package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.dto.AudioManifestDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ListeningSessionWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PlaybackStateDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PlaybackWriteDto
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import app.tfdn.lirelle.source.bookorbit.network.fileIdOf
import app.tfdn.lirelle.source.bookorbit.network.revisionOf
import app.tfdn.lirelle.source.bookorbit.network.versionOf
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** A write on a revision that is no longer the state's: a newer state stands. */
private const val StaleRevision = 409

/** A write in a manifest revision that is no longer the book's: its files changed. */
private const val StaleManifest = 412
private val Percentages = 0.0..100.0

/**
 * BookOrbit's audiobooks. Its listening states carry an integer revision, which the app holds as an opaque version
 * ([versionOf]); a write is sent on the base revision its version names ([revisionOf]: none is 0, which creates the
 * state), with the app's operation id; a 409 is a write overtaken, a 412 files that changed. The manifest's revision
 * is the audiobook's content version.
 *
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 */
internal class DefaultAudioRepository(
    private val api: ApiProvider<AudioApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
    private val json: Json = ApiJson,
) : AudioRepository {

    /** The server is read once for a call: the API and the addresses [block] builds are of the same one. */
    private suspend fun <T> callOn(block: suspend (AudioApi, String) -> T): ApiResult<T> {
        val server = serverUrl() ?: return ApiResult.Failure(ApiError.SessionExpired)
        val remote = api.forServer(server)
        return errors.call(CallKind.Authenticated) { block(remote, server) }
    }

    private suspend fun <T> call(block: suspend (AudioApi) -> T): ApiResult<T> = callOn { remote, _ -> block(remote) }

    override suspend fun manifest(bookId: BookId): ApiResult<AudioManifest> =
        call { it.manifest(bookId.bookOrbit()).toModel() }

    override fun assetUrl(bookId: BookId, assetId: String): String? =
        serverUrl()?.let { "$it/api/v1/audiobooks/${bookId.bookOrbit()}/assets/$assetId/content" }

    override suspend fun playbackState(bookId: BookId): ApiResult<PlaybackState?> = call { api ->
        val text = api.playbackState(bookId.bookOrbit()).use { it.string() }.trim()
        // No state: an empty body, or the literal null.
        if (text.isEmpty() || text == "null") null else json.decodeFromString<PlaybackStateDto>(text).toModel()
    }

    override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite): ApiResult<PlaybackWriteResult> =
        callOn<PlaybackWriteResult?> { api, _ ->
            val body = PlaybackWriteDto(
                assetId = write.assetId,
                positionMs = write.positionMs,
                // An Instant prints as strict ISO-8601 in UTC, which is what the server checks.
                capturedAt = write.capturedAt.toString(),
                operationId = write.operationId,
                // After an overtaken write, the app makes it again over the state it read: its revision.
                baseRevision = revisionOf(write.baseVersion),
                manifestRevision = write.contentVersion,
            )
            val response = api.savePlaybackState(bookId.bookOrbit(), body)
            when (response.code()) {
                StaleRevision -> PlaybackWriteResult.Overtaken
                StaleManifest -> PlaybackWriteResult.ContentChanged
                else -> {
                    if (!response.isSuccessful) throw HttpException(response)
                    response.body()?.let { PlaybackWriteResult.Saved(it.toModel()) }
                }
            }
        }.let { result ->
            // A 2xx without a state cannot be told from a lost write: neither a save nor a conflict.
            when (result) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> result.value?.let { ApiResult.Success(it) }
                    ?: ApiResult.Failure(ApiError.Unexpected)
            }
        }

    override suspend fun saveListeningSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> =
        call { api ->
            api.saveSession(
                fileId.bookOrbit(),
                ListeningSessionWriteDto(
                    sessionId = session.sessionId,
                    startedAt = session.startedAt.toString(),
                    endedAt = session.endedAt.toString(),
                    durationSeconds = session.activeSeconds.coerceAtLeast(0),
                    progressDelta = session.progressDelta,
                    endProgress = session.endPercent?.coerceIn(Percentages),
                ),
            )
        }

    override suspend fun nextAudioVolume(seriesId: SeriesId, bookId: BookId): ApiResult<NextVolume?> = call { api ->
        api.nextVolume(seriesId.bookOrbit(), bookId.bookOrbit()).next
            ?.let { NextVolume(bookIdOf(it.bookId), it.title, it.seriesIndex) }
    }
}

private fun AudioManifestDto.toModel(): AudioManifest {
    val audio = assets.sortedBy { it.sequence }.map {
        // An unknown duration comes as nothing, or as 0.
        val duration = it.durationMs?.takeIf { ms -> ms > 0 }
        AudioAsset(it.assetId, fileIdOf(it.fileId), it.sequence, it.format, duration, it.sizeBytes, it.etag)
    }
    return AudioManifest(
        bookId = bookIdOf(book.id),
        contentVersion = revision,
        title = book.title,
        authors = book.authors,
        narrators = book.narrators,
        hasCover = book.hasCover,
        assets = audio,
        chapters = chapters.sortedBy { it.sequence }.map {
            AudioChapter(it.id.content, it.title, it.assetId, it.sequence, it.startMs, it.endMs, it.assetOffsetMs)
        },
        totalDurationMs = totalDurationMs ?: audio.sumOf { it.durationMs ?: 0 },
    )
}

private fun PlaybackStateDto.toModel() = PlaybackState(
    assetId = assetId,
    positionMs = positionMs,
    percentage = percentage,
    completed = completed,
    capturedAt = instant(capturedAt),
    version = versionOf(revision),
    contentVersion = manifestRevision,
)
