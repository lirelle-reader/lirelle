package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import java.time.Instant

/**
 * A place in an audiobook the user marked.
 *
 * @property id the UUID the app made for it, which the server keeps as its id (the `clientId` it was sent with).
 * @property positionMs on the whole book's timeline.
 * @property chapterId the manifest's chapter, when it was known.
 * @property createdAt null for a bookmark the server has not answered yet.
 */
data class AudioBookmark(
    val id: String,
    val bookId: BookId,
    val positionMs: Long,
    val chapterId: String?,
    val title: String,
    val note: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        private const val MillisPerSecond = 1000L

        /** Position order; at the same position, the older first (one not answered yet has the device's time). */
        val PositionOrder: Comparator<AudioBookmark> = compareBy({ it.positionMs }, { it.createdAt })

        /** The whole second [positionMs] is in: the server keeps one bookmark per second. */
        fun secondOf(positionMs: Long): Long = positionMs / MillisPerSecond
    }
}

sealed interface BookmarkCreateResult {
    data class Created(val bookmark: AudioBookmark) : BookmarkCreateResult

    /** Another bookmark is at that exact second (409). */
    data object SameSecond : BookmarkCreateResult
}
