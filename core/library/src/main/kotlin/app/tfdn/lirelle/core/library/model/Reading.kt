package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import java.time.Instant

/**
 * Where the user is in a file, as the server holds it.
 *
 * @property cfi the place; null when the server only knows a percentage (after an e-reader's write), and for
 *   a file read by pages.
 * @property percent from 0 to 100.
 * @property updatedAt when the text position last moved; null for a file never opened.
 * @property narration where the narration is; null when it never played, or the server holds no place for it.
 * @property narrationUpdatedAt when the narration position last moved.
 * @property page the page of a file read by pages (a comic), as the server holds it; null for a book.
 */
data class ReadingPosition(
    val fileId: FileId,
    val cfi: String?,
    val percent: Double,
    val updatedAt: Instant?,
    val narration: NarrationPlace? = null,
    val narrationUpdatedAt: Instant? = null,
    val page: Int? = null,
)

/** The sentence a narration is at: the server's narration fields. [fragment] is `textHref#id`. */
data class NarrationPlace(val seconds: Double, val fragment: String, val sectionIndex: Int)

/** [Superseded]: the server held a newer position and kept it. */
enum class SaveOutcome { Saved, Superseded }

/**
 * What the server did with a position sent to it.
 *
 * @property storedAt the time of the position the server holds after the write, by the server's
 * own clock: ours when [outcome] is [SaveOutcome.Saved], the one it kept otherwise. Null when the
 * answer does not say.
 */
data class SavedPosition(val outcome: SaveOutcome, val storedAt: Instant? = null)

/**
 * One sitting. [sessionId] is made by the device; sending the same id again only ever lengthens
 * the session the server holds.
 *
 * @property activeSeconds the time actually spent reading.
 * @property progressDelta the percentage points gained, negative when the user went back.
 * @property endPercent the position reached, from 0 to 100.
 */
data class ReadingSessionReport(
    val sessionId: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val activeSeconds: Int,
    val progressDelta: Double?,
    val endPercent: Double?,
)

/**
 * A finished reading of a book.
 *
 * @property lastChangedAt when the server last wrote it: its end, or the rating given since, which comes
 *   just after; null when unknown.
 * @property rating the stars given to it, 1 to 5; null when none.
 */
data class FinishedReading(val id: ReadId, val lastChangedAt: Instant?, val rating: Int?)

/** @property seriesIndex the volume number, as the server writes it ("3", "2.5"). */
data class NextVolume(val bookId: BookId, val title: String?, val seriesIndex: String?)

/** Among which files of a series the next volume is looked for: the books to read, or the comics. */
enum class VolumeGroup { Epub, Comics }

/** @property totalBytes null when the server does not announce a length. */
data class DownloadProgress(val bytesRead: Long, val totalBytes: Long?)
