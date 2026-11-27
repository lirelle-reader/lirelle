package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiResult
import java.io.OutputStream
import java.time.Instant

/**
 * An Audiobookshelf profile's reading until its books are read (#69): no file, no position, nothing to save to — and
 * the library has no book to open. The server is asked nothing.
 */
internal class EmptyAbsReader : ReaderRepository {
    override suspend fun position(fileId: FileId): ApiResult<ReadingPosition> = NotFound

    override suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant?,
        page: Int?,
    ): ApiResult<SavedPosition> = NotFound

    override suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant?,
    ): ApiResult<SavedPosition> = NotFound

    override suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> = NotFound

    override suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?> = ApiResult.Success(null)

    override suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit> = NotFound

    override suspend fun nextVolume(seriesId: SeriesId, bookId: BookId, group: VolumeGroup): ApiResult<NextVolume?> =
        ApiResult.Success(null)

    override fun fileUrl(fileId: FileId, audioless: Boolean): String? = null

    override suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> = NotFound
}

/** An Audiobookshelf profile's listening until its audiobooks are played (#68): nothing to play, nothing saved. */
internal class EmptyAbsAudio : AudioRepository {
    override suspend fun manifest(bookId: BookId): ApiResult<AudioManifest> = NotFound

    override fun assetUrl(bookId: BookId, assetId: String): String? = null

    override suspend fun playbackState(bookId: BookId): ApiResult<PlaybackState?> = ApiResult.Success(null)

    override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite): ApiResult<PlaybackWriteResult> =
        NotFound

    override suspend fun saveListeningSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> =
        NotFound

    override suspend fun nextAudioVolume(seriesId: SeriesId, bookId: BookId): ApiResult<NextVolume?> =
        ApiResult.Success(null)
}

/** An Audiobookshelf profile's audio bookmarks until its audiobooks are played (#68): none is kept, none is made. */
internal class EmptyAbsAudioBookmarks : AudioBookmarkRepository {
    override suspend fun list(bookId: BookId): ApiResult<List<AudioBookmark>> = none()

    override suspend fun create(bookId: BookId, bookmark: AudioBookmark): ApiResult<BookmarkCreateResult> = NotFound

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?): ApiResult<AudioBookmark> =
        NotFound

    override suspend fun delete(bookId: BookId, id: String): ApiResult<Unit> = NotFound
}

/** An Audiobookshelf profile's marks until its books are read (#69): none is kept, none is made. */
internal class EmptyAbsMarks : MarksRepository {
    override suspend fun bookmarks(bookId: BookId, fileId: FileId): ApiResult<List<ServerBookmark>> = none()

    override suspend fun addBookmark(
        bookId: BookId,
        fileId: FileId,
        cfi: String,
        title: String,
    ): ApiResult<ServerBookmark> = NotFound

    override suspend fun deleteBookmark(bookId: BookId, id: MarkId): ApiResult<Unit> = NotFound

    override suspend fun highlights(bookId: BookId, fileId: FileId): ApiResult<List<ServerHighlight>> = none()

    override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): ApiResult<ServerHighlight> = NotFound

    override suspend fun changeHighlight(
        bookId: BookId,
        id: MarkId,
        color: String?,
        note: String?,
        clearNote: Boolean,
    ): ApiResult<ServerHighlight> = NotFound

    override suspend fun deleteHighlight(bookId: BookId, id: MarkId): ApiResult<Unit> = NotFound

    override suspend fun restoreHighlight(id: MarkId): ApiResult<Unit> = NotFound
}

/** Audiobookshelf has no narration aligned with a book's text: no read-along, for good. */
internal class EmptyAbsReadAlong : ReadAlongRepository {
    override suspend fun playlist(bookId: BookId, fileId: FileId): ApiResult<ReadAlongPlaylist> = NotFound

    override suspend fun spine(bookId: BookId, fileId: FileId): ApiResult<List<SpineEntry>> = none()

    override fun trackUrl(bookId: BookId, fileId: FileId, audioHref: String): String? = null
}
