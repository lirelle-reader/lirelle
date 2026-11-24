package app.tfdn.lirelle.core.audio.bookmarks

import app.tfdn.lirelle.core.audio.position.InstantAsText
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// How a [BookmarkState] is kept as JSON.

@Serializable
internal data class StoredBookmark(
    val id: String,
    val bookId: BookId,
    val positionMs: Long,
    val chapterId: String? = null,
    val title: String,
    val note: String? = null,
    @Serializable(InstantAsText::class) val createdAt: Instant? = null,
    @Serializable(InstantAsText::class) val updatedAt: Instant? = null,
) {
    fun model() = AudioBookmark(id, bookId, positionMs, chapterId, title, note, createdAt, updatedAt)

    companion object {
        fun of(b: AudioBookmark) =
            StoredBookmark(b.id, b.bookId, b.positionMs, b.chapterId, b.title, b.note, b.createdAt, b.updatedAt)
    }
}

@Serializable
internal sealed interface StoredChange {
    fun change(): BookmarkChange

    @Serializable
    @SerialName("add")
    data class Add(val bookmark: StoredBookmark, val attempted: Boolean = false) : StoredChange {
        override fun change() = BookmarkChange.Add(bookmark.model(), attempted)
    }

    @Serializable
    @SerialName("edit")
    data class Edit(val bookId: BookId, val id: String, val title: String, val note: String? = null) : StoredChange {
        override fun change() = BookmarkChange.Edit(bookId, id, title, note)
    }

    @Serializable
    @SerialName("delete")
    data class Delete(val bookId: BookId, val id: String) : StoredChange {
        override fun change() = BookmarkChange.Delete(bookId, id)
    }
}

@Serializable
internal data class StoredServerBook(val bookmarks: List<StoredBookmark> = emptyList(), val version: Int = 0)

/**
 * What is kept of the bookmarks. A value written before the profiles also names an `owner`, which is no longer
 * read: the file is in a profile's folder, and holds nobody else's.
 */
@Serializable
internal data class StoredBookmarkState(
    val books: Map<BookId, StoredServerBook> = emptyMap(),
    val changes: List<StoredChange> = emptyList(),
) {
    fun state() = BookmarkState(
        books = books.mapValues { (_, book) -> ServerBook(book.bookmarks.map { it.model() }, book.version) },
        changes = changes.map { it.change() },
    )
}

internal fun BookmarkState.stored() = StoredBookmarkState(
    books = books.mapValues { (_, book) -> StoredServerBook(book.bookmarks.map(StoredBookmark::of), book.version) },
    changes = changes.map { change ->
        when (change) {
            is BookmarkChange.Add -> StoredChange.Add(StoredBookmark.of(change.bookmark), change.attempted)
            is BookmarkChange.Edit -> StoredChange.Edit(change.bookId, change.id, change.title, change.note)
            is BookmarkChange.Delete -> StoredChange.Delete(change.bookId, change.id)
        }
    },
)
