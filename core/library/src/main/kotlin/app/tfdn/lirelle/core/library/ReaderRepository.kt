package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiResult
import java.io.OutputStream
import java.time.Instant

/** What the reader asks the server and tells it. Percentages go from 0 to 100, as on the server. */
interface ReaderRepository {
    /** A file never opened is at 0, with no place and no date. */
    suspend fun position(fileId: FileId): ApiResult<ReadingPosition>

    /**
     * The server takes any saved position above 0 as "the user is reading this book".
     *
     * @param editedAt when the position was made, for one that is sent late: the server then sets
     * it aside ([SaveOutcome.Superseded]) if it holds a newer one. Null for the position the user
     * is at right now, which always lands: the server compares [editedAt] with its own clock, and a
     * device whose clock is a little behind must not have its live position refused.
     * @param page the page of a file read by pages (a comic), which has no [cfi]; null for a book.
     */
    suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant? = null,
        page: Int? = null,
    ): ApiResult<SavedPosition>

    /**
     * The place of the narration of a read-along book, written like [savePosition] (same [editedAt] rule, same
     * answer), but with no text part: the text position is left as it is.
     */
    suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant? = null,
    ): ApiResult<SavedPosition>

    /** The server silently drops a session of less than 10 seconds. */
    suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit>

    /** The id of the newest finished reading of the book, or null when there is none yet. */
    suspend fun finishedReadingId(bookId: BookId): ApiResult<ReadId?> = when (val reading = finishedReading(bookId)) {
        is ApiResult.Failure -> reading
        is ApiResult.Success -> ApiResult.Success(reading.value?.id)
    }

    /** The newest finished reading of the book, with when it was last written and its rating; null when none. */
    suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?>

    /** [rating] from 1 to 5, or null to remove it. Only a finished reading can be rated. */
    suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit>

    suspend fun nextVolume(
        seriesId: SeriesId,
        bookId: BookId,
        group: VolumeGroup = VolumeGroup.Epub,
    ): ApiResult<NextVolume?>

    /**
     * The absolute URL of a file's content, for a download kept on the device: the file as it is, or the
     * read-along EPUB without its audio when [audioless]. Null only when the profile has no server to give. Where
     * a file is served is the source's to say.
     */
    fun fileUrl(fileId: FileId, audioless: Boolean): String?

    /**
     * Streams the file into [sink]; the audio-less variant when [audioless].
     *
     * [sink] is neither closed nor cleaned up: after a failure or a cancellation it holds the
     * start of the file, and removing that is the caller's business. [onProgress] is called on
     * the thread that copies, once per block read.
     */
    suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit>
}
