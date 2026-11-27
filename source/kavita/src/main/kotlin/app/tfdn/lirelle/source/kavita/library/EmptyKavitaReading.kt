package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.network.ApiResult

/** Kavita has no audio at all. */
internal class EmptyKavitaAudio : AudioRepository {
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

/** Kavita has no audio, so no bookmark of it. */
internal class EmptyKavitaAudioBookmarks : AudioBookmarkRepository {
    override suspend fun list(bookId: BookId): ApiResult<List<AudioBookmark>> = none()

    override suspend fun create(bookId: BookId, bookmark: AudioBookmark): ApiResult<BookmarkCreateResult> = NotFound

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?): ApiResult<AudioBookmark> =
        NotFound

    override suspend fun delete(bookId: BookId, id: String): ApiResult<Unit> = NotFound
}

/** Kavita keeps no bookmark nor highlight of a book: none is kept, none is made. */
internal class EmptyKavitaMarks : MarksRepository {
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

/** Kavita has no narration. */
internal class EmptyKavitaReadAlong : ReadAlongRepository {
    override suspend fun playlist(bookId: BookId, fileId: FileId): ApiResult<ReadAlongPlaylist> = NotFound

    override suspend fun spine(bookId: BookId, fileId: FileId): ApiResult<List<SpineEntry>> = none()

    override fun trackUrl(bookId: BookId, fileId: FileId, audioHref: String): String? = null
}
