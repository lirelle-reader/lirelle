package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.network.ApiResult

interface AudioRepository {
    suspend fun manifest(bookId: BookId): ApiResult<AudioManifest>

    /** The absolute URL of an asset's content, for the player's data source (the shared OkHttp client signs it). */
    fun assetUrl(bookId: BookId, assetId: String): String?

    /** Null when the book has none yet. */
    suspend fun playbackState(bookId: BookId): ApiResult<PlaybackState?>

    /** A newer state standing, or files that changed, is an answer ([PlaybackWriteResult]), not a failure. */
    suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite): ApiResult<PlaybackWriteResult>

    /** A listening sitting: `sessionType` "listen". */
    suspend fun saveListeningSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit>

    /** The next volume of the series that has audio, or null. */
    suspend fun nextAudioVolume(seriesId: SeriesId, bookId: BookId): ApiResult<NextVolume?>
}
