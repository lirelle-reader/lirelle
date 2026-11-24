package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import java.time.Instant

/**
 * One audio file of a book. [assetId] is the server's public id of the asset, [fileId] the id of the book's file.
 *
 * @property durationMs null when the server does not know it (it gives none, or 0).
 */
data class AudioAsset(
    val assetId: String,
    val fileId: FileId,
    val sequence: Int,
    val format: String,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val etag: String?,
)

/** A chapter on the book's timeline; [assetOffsetMs] is where it starts inside [assetId]. */
data class AudioChapter(
    val id: String,
    val title: String,
    val assetId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val assetOffsetMs: Long,
)

/**
 * @property contentVersion the version of the book's set of files, as the source gives it (text, compared for
 * equality only): every position is placed in one, and written with it; the download of the audiobook is of one.
 */
data class AudioManifest(
    val bookId: BookId,
    val contentVersion: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val hasCover: Boolean,
    val assets: List<AudioAsset>,
    val chapters: List<AudioChapter>,
    val totalDurationMs: Long,
)

/**
 * Where the listening of a book stands on the server.
 *
 * @property positionMs inside [assetId].
 * @property percentage of the whole book, 0 to 100.
 * @property version this state's version, opaque: the app compares it for equality only, never orders it. A state
 * whose version is not the one the device knows was written elsewhere.
 * @property contentVersion the [AudioManifest.contentVersion] the position was placed in; null when the source
 * does not say.
 */
data class PlaybackState(
    val assetId: String,
    val positionMs: Long,
    val percentage: Double,
    val completed: Boolean,
    val capturedAt: Instant?,
    val version: String,
    val contentVersion: String?,
)

/**
 * A position to write.
 *
 * @property operationId the app's id of the write: the same position sent again keeps it, so that the source never
 * applies it twice.
 * @property baseVersion the version of the state it is made over (the one the device last knew, or answered);
 * null when the device knows none: it creates the book's state.
 * @property contentVersion the [AudioManifest.contentVersion] [assetId] comes from.
 */
data class PlaybackWrite(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: Instant,
    val operationId: String,
    val baseVersion: String?,
    val contentVersion: String,
)

sealed interface PlaybackWriteResult {
    /** Written: [state] is the new one, with its version. */
    data class Saved(val state: PlaybackState) : PlaybackWriteResult

    /**
     * A newer state stands: someone else (another device, or the text of a read-along book) wrote since
     * [PlaybackWrite.baseVersion].
     */
    data object Overtaken : PlaybackWriteResult

    /** The book's set of files changed: the position is placed again in the new manifest. */
    data object ContentChanged : PlaybackWriteResult
}
