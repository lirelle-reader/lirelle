package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.PositionStatus
import java.time.Instant

/**
 * The id the server gives an item, apart from [MarkId], the app's own id of it here: named so in this
 * package, where both meet.
 */
typealias ServerMarkId = app.tfdn.lirelle.core.ids.MarkId

/**
 * The app's own id of an item: stable before and after the server gave it one. For an item first
 * seen on the server, `b<serverId>` for a bookmark and `h<serverId>` for a highlight (the server
 * may name them apart, so one prefix would let bookmark 5 and highlight 5 share an id); a UUID
 * otherwise.
 */
@JvmInline
value class MarkId(val value: String) {
    companion object {
        /** The id of a bookmark first seen on the server. */
        fun ofBookmark(serverId: ServerMarkId) = MarkId("b${serverId.value}")

        /** The id of a highlight first seen on the server. */
        fun ofHighlight(serverId: ServerMarkId) = MarkId("h${serverId.value}")
    }
}

/** [serverId] is null until the server took the bookmark; [cfi] null when it cannot be placed. */
data class Bookmark(
    val id: MarkId,
    val serverId: ServerMarkId?,
    val cfi: String?,
    val title: String,
    val note: String?,
    val origin: MarkOrigin,
    val createdAt: Instant,
)

/** [serverId] is null until the server took the highlight; [cfi] null when it cannot be placed. */
data class Highlight(
    val id: MarkId,
    val serverId: ServerMarkId?,
    val cfi: String?,
    val text: String,
    val color: String,
    val style: HighlightStyle,
    val note: String?,
    val chapterTitle: String?,
    val chapterIndex: Int?,
    val origin: MarkOrigin,
    val positionStatus: PositionStatus?,
    val createdAt: Instant,
)

/** One book's marks, as shown: the server's, with the changes still waiting applied. */
data class BookMarks(val bookmarks: List<Bookmark> = emptyList(), val highlights: List<Highlight> = emptyList())

/**
 * A book as the store holds it, deleted items included ([MarksStore.held]).
 *
 * @property fileId the file the places are given in: the one of the last refresh, or of the last
 * mark made here; null when there was neither.
 */
data class HeldBook(
    val fileId: FileId? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
)

/** A change made on this device, applied to the [MarksStore] at once and sent through the [MarkOutbox]. */
sealed interface MarkChange {
    val bookId: BookId
    val id: MarkId

    /**
     * [attemptedAt]: stamped just before the first send, and kept: from then on the highlight may be
     * on the server, even when the answer was lost.
     */
    data class AddHighlight(
        override val bookId: BookId,
        override val id: MarkId,
        val fileId: FileId,
        val highlight: NewHighlight,
        val attemptedAt: Instant? = null,
    ) : MarkChange

    /** A null field is left as it is. */
    data class ChangeHighlight(
        override val bookId: BookId,
        override val id: MarkId,
        val color: String?,
        val note: NoteEdit?,
    ) : MarkChange

    data class DeleteHighlight(override val bookId: BookId, override val id: MarkId) : MarkChange

    data class RestoreHighlight(override val bookId: BookId, override val id: MarkId) : MarkChange

    data class AddBookmark(
        override val bookId: BookId,
        override val id: MarkId,
        val fileId: FileId,
        val cfi: String,
        val title: String,
    ) : MarkChange

    data class DeleteBookmark(override val bookId: BookId, override val id: MarkId) : MarkChange
}

/** What becomes of a highlight's note. */
sealed interface NoteEdit {
    data class Set(val text: String) : NoteEdit

    data object Clear : NoteEdit
}
