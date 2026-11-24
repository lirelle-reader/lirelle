package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [AudioRepository]. Set the result fields, then read what was asked.
 * [gate], when set, holds every suspending call until it is completed.
 */
class FakeAudioRepository : AudioRepository {
    var manifestResult: ApiResult<AudioManifest> = ApiResult.Failure(ApiError.ServerUnreachable)
    var playbackStateResult: ApiResult<PlaybackState?> = ApiResult.Success(null)

    /** Per book, in place of [manifestResult]. */
    var manifests: Map<BookId, ApiResult<AudioManifest>> = emptyMap()

    /** Per book, in place of [playbackStateResult]. */
    var playbackStates: Map<BookId, ApiResult<PlaybackState?>> = emptyMap()

    /** The answer to each write in turn; the last one repeats. */
    var saveResults: List<ApiResult<PlaybackWriteResult>> = listOf(ApiResult.Failure(ApiError.ServerUnreachable))
    var saveSessionResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var nextVolumeResult: ApiResult<NextVolume?> = ApiResult.Success(null)
    var serverUrl: String? = "https://books.example"
    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()
    val writes = mutableListOf<Pair<BookId, PlaybackWrite>>()
    val savedSessions = mutableListOf<Pair<FileId, ReadingSessionReport>>()

    private suspend fun <T> answer(call: String, result: T): T {
        calls += call
        gate?.await()
        return result
    }

    override suspend fun manifest(bookId: BookId) = answer("manifest $bookId", manifests[bookId] ?: manifestResult)

    /** Under no server's routes: `<server>/audio/<bookId>/<assetId>`. */
    override fun assetUrl(bookId: BookId, assetId: String): String? =
        serverUrl?.let { "$it/audio/$bookId/$assetId" }

    override suspend fun playbackState(bookId: BookId) =
        answer("playbackState $bookId", playbackStates[bookId] ?: playbackStateResult)

    override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite): ApiResult<PlaybackWriteResult> {
        writes += bookId to write
        return answer("savePlaybackState $bookId", saveResults[(writes.size - 1).coerceAtMost(saveResults.lastIndex)])
    }

    override suspend fun saveListeningSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> {
        savedSessions += fileId to session
        return answer("saveListeningSession $fileId ${session.sessionId}", saveSessionResult)
    }

    override suspend fun nextAudioVolume(seriesId: SeriesId, bookId: BookId) =
        answer("nextAudioVolume $seriesId $bookId", nextVolumeResult)
}
