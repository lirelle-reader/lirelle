package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.reader.sync.InstantAsText
import java.time.Instant
import kotlinx.serialization.Serializable

/** How many books the store keeps the marks of, besides those with a change waiting. */
private const val KeptBooks = 30

/** What the store keeps of an item, shown or not. */
internal interface StoredMark {
    val id: String
    val serverId: ServerMarkId?

    /** Deleted here: hidden, and kept so that a waiting delete hides the server's copy and a restore can show it. */
    val deleted: Boolean
}

@Serializable
internal data class StoredBookmark(
    override val id: String,
    override val serverId: ServerMarkId? = null,
    val cfi: String? = null,
    val title: String,
    val note: String? = null,
    val origin: MarkOrigin,
    @Serializable(InstantAsText::class) val createdAt: Instant,
    override val deleted: Boolean = false,
) : StoredMark

@Serializable
internal data class StoredHighlight(
    override val id: String,
    override val serverId: ServerMarkId? = null,
    val cfi: String? = null,
    val text: String,
    val color: String,
    val style: HighlightStyle,
    val note: String? = null,
    val chapterTitle: String? = null,
    val chapterIndex: Int? = null,
    val origin: MarkOrigin,
    val positionStatus: PositionStatus? = null,
    @Serializable(InstantAsText::class) val createdAt: Instant,
    override val deleted: Boolean = false,
) : StoredMark

/** One book's marks, shown or remembered. [fileId]: the file their places are given in (see [HeldBook]). */
@Serializable
internal data class StoredBook(
    val bookId: BookId,
    val fileId: FileId? = null,
    val bookmarks: List<StoredBookmark> = emptyList(),
    val highlights: List<StoredHighlight> = emptyList(),
)

/**
 * The marks of each book and the changes still to be sent, oldest first, with those a merge
 * cancelled (see [MarkQueue]). One value, so that a change is shown and queued in one write.
 * [books] are in the order they were last written, the least recent first ([withBook]).
 *
 * A value written before the profiles also names an `owner`, which is no longer read: the file is in
 * a profile's folder, and holds nobody else's.
 */
@Serializable
internal data class StoredMarksState(
    val books: List<StoredBook> = emptyList(),
    val changes: List<StoredChange> = emptyList(),
    val cancelled: List<StoredChange> = emptyList(),
) {
    fun book(bookId: BookId): StoredBook = books.firstOrNull { it.bookId == bookId } ?: StoredBook(bookId)

    fun withBook(bookId: BookId, transform: (StoredBook) -> StoredBook): StoredMarksState =
        copy(books = books.filterNot { it.bookId == bookId } + transform(book(bookId)))

    fun queue() = MarkQueue(changes.map { it.change() }, cancelled.map { it.change() })

    fun withQueue(queue: MarkQueue) =
        copy(changes = queue.changes.map { it.stored() }, cancelled = queue.cancelled.map { it.stored() })

    /** The items of [bookId] with a change waiting. */
    fun queued(bookId: BookId): Set<String> = changes.filter { it.bookId == bookId }.map { it.id }.toSet()

    /**
     * At most [kept] books, unless more have a change waiting: the least recently written without
     * one go first. They are read again from the server when opened.
     */
    fun trimmed(kept: Int = KeptBooks): StoredMarksState {
        val extra = books.size - kept
        if (extra <= 0) return this
        val waiting = changes.map { it.bookId }.toSet()
        val dropped = books.asSequence().map { it.bookId }.filterNot { it in waiting }.take(extra).toSet()
        return copy(books = books.filterNot { it.bookId in dropped })
    }
}

internal fun StoredMarksState.applied(change: MarkChange, now: Instant) =
    withBook(change.bookId) { it.applied(change, now) }

internal fun StoredMarksState.enqueued(change: MarkChange) = withQueue(queue().enqueued(change))

internal fun StoredBook.shown() = BookMarks(
    bookmarks = bookmarks.filterNot { it.deleted }.map { it.bookmark() }.sortedWith(compareBy(CfiOrder) { it.cfi }),
    highlights = highlights.filterNot { it.deleted }.map { it.highlight() }.sortedWith(compareBy(CfiOrder) { it.cfi }),
)

internal fun StoredBook.held() = HeldBook(fileId, bookmarks.map { it.bookmark() }, highlights.map { it.highlight() })

private fun StoredBookmark.bookmark() = Bookmark(MarkId(id), serverId, cfi, title, note, origin, createdAt)

private fun StoredHighlight.highlight() = Highlight(
    MarkId(id), serverId, cfi, text, color, style, note, chapterTitle, chapterIndex, origin, positionStatus, createdAt,
)

internal fun ServerBookmark.stored(id: String) = StoredBookmark(id, this.id, cfi, title, note, origin, createdAt)

internal fun ServerHighlight.stored(id: String) = StoredHighlight(
    id, this.id, cfi, text, color, style, note, chapterTitle, chapterIndex, origin, positionStatus, createdAt,
)
