package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import kotlinx.coroutines.flow.Flow

/**
 * The bookmarks and highlights of each book opened, kept across restarts so that they show at
 * once and offline: the server's, with the changes waiting in the [MarkOutbox] applied. Both are
 * one state ([DataStoreMarks]), written together. Safe to use from several coroutines at once.
 */
interface MarksStore {
    /** The book's marks as shown, in reading order; empty for a book never seen. */
    fun book(bookId: BookId): Flow<BookMarks>

    /**
     * The server's lists replace what is held, except for the items with a change waiting in the
     * outbox at the moment of this write, which keep their local state, deleted ones staying
     * hidden. An item first seen on the server is [MarkId.ofBookmark] or [MarkId.ofHighlight]; one
     * bound here keeps its id.
     * [fileId] is the file the lists were read for: it becomes the book's (see [HeldBook]); null keeps it.
     * Beyond 30 books, the least recently written with no change waiting are forgotten: they are
     * read again when opened.
     */
    suspend fun replaceFromServer(
        bookId: BookId,
        bookmarks: List<ServerBookmark>,
        highlights: List<ServerHighlight>,
        fileId: FileId? = null,
    )

    /**
     * Everything held of the book, deleted items included, in no order: what the flusher needs of
     * an item (its server id, its values) and what an undo brings back.
     */
    suspend fun held(bookId: BookId): HeldBook

    /**
     * Shows a change made here, without queuing it. Callers use [MarkOutbox.record], which does
     * both in one write; this is for tests and the store's own use. A deleted highlight is
     * remembered, so that a restore brings it back as it was, even after the delete was sent and
     * the book refreshed. A change of an item the store does not hold changes nothing.
     */
    suspend fun apply(change: MarkChange)

    /**
     * The item [id] is known to the server as [serverId]; its id does not change. A copy of the
     * same server item that a refresh brought meanwhile (`b<serverId>` or `h<serverId>`), with no
     * change waiting, is dropped.
     */
    suspend fun bindServerId(bookId: BookId, id: MarkId, serverId: ServerMarkId)
}
