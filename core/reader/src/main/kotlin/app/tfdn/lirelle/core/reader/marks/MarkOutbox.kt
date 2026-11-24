package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.SourceCapabilities

/**
 * The changes of bookmarks and highlights still to be sent, oldest first, kept across restarts in
 * one state with the [MarksStore] ([DataStoreMarks]). Safe to use from several coroutines at once.
 *
 * The changes of one item (the same id in the same book) waiting together are merged:
 * - an add then changes: one add with the final values; an add then a delete: nothing;
 * - changes: one change, a later field winning; changes then a delete: the delete; a change after
 *   a delete: dropped;
 * - a delete then a restore: nothing; a restore then a delete: nothing; a restore after an add
 *   that a delete cancelled: the add again;
 * - a bookmark added then deleted: nothing; deleted then added again: only added.
 * An add stamped [MarkChange.AddHighlight.attemptedAt] never absorbs anything: its changes and its
 * delete are kept apart from it, sent after.
 *
 * Rules for the flusher:
 * - **Stamp before sending.** Before the first send of an add, it calls
 *   `replace(add, add.copy(attemptedAt = now))`, then sends the add only as `pending()` gives it
 *   back stamped (if it is gone or unstamped, a merge came first: read `pending()` again). So an
 *   add that may be on the server is always stamped, and never merged into or cancelled. It never
 *   re-stamps an add already stamped: the first stamp is kept.
 * - **Duplicate check.** It runs only for an add that was already stamped when read from
 *   `pending()`: a send of it may have left. A server highlight "already bound to another MarkId"
 *   means a local item (id not `h<serverId>`) carrying that server id; items a refresh brought
 *   (`h<id>`) do not count.
 * - **Cancelled while in flight.** After a retryable failure, if the change is no longer in
 *   `pending()` (a merge cancelled it while it was being sent), it may have left: the flusher
 *   calls [markSent] with it, which queues the change that undoes it; that one is sent like any
 *   other (a 404 on a delete counts as done).
 * - **Orphans.** A change of an item with no server id whose add is no longer queued can never be
 *   sent: the flusher drops it with [markSent].
 */
interface MarkOutbox {
    /**
     * A change made here: shown in the store and queued, in one write, so that a refresh never
     * sees one without the other. This is what callers use.
     */
    suspend fun record(change: MarkChange)

    /** Queues [change], merged with what waits for the same item, without showing it: see [record]. */
    suspend fun enqueue(change: MarkChange)

    /** What waits, oldest first. */
    suspend fun pending(): List<MarkChange>

    /** Puts [new] in the place of [old] if [old] is still exactly there; otherwise nothing changes. */
    suspend fun replace(old: MarkChange, new: MarkChange)

    /**
     * The server took [change], or it was given up. Removes it if it is still exactly there. If a
     * merge cancelled it (an add or a delete undone before it was sent), the change that undoes it
     * is queued first, since the server may hold it.
     */
    suspend fun markSent(change: MarkChange)

    /**
     * The server does not have the highlight [id] of [bookId]: a change of it was answered 404
     * (deleted elsewhere; [noteTyped] when that change typed a note), or it is [restored] here
     * after its add was dropped. In one write, its changes waiting go and its server id is
     * forgotten. Then, if it is shown and can be placed, an add of the values shown now (every
     * edit made here included) is queued when it is [restored], or when a note was typed (by that
     * change or one waiting) and the note shown is not blank. Otherwise it is not worth making
     * again (a colour alone, a note typed then cleared) and is hidden; one deleted here stays
     * deleted.
     */
    suspend fun remake(bookId: BookId, id: MarkId, noteTyped: Boolean = false, restored: Boolean = false)
}

/**
 * This outbox for a profile whose source can do [capabilities] (spec 2c): without annotations, no change is
 * shown nor queued, and none is made again; what waits (nothing) is read and settled as ever.
 */
fun MarkOutbox.forSource(capabilities: SourceCapabilities): MarkOutbox =
    if (capabilities.annotations) this else WithoutAnnotations(this)

private class WithoutAnnotations(outbox: MarkOutbox) : MarkOutbox by outbox {
    override suspend fun record(change: MarkChange) = Unit

    override suspend fun enqueue(change: MarkChange) = Unit

    override suspend fun remake(bookId: BookId, id: MarkId, noteTyped: Boolean, restored: Boolean) = Unit
}
