package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Clock

/** What one step of a flush came to. */
internal enum class FlushStep {
    /** A change of the book went (or was given up): its lists are to be read again. */
    Sent,

    /** Nothing went: read the queue again (an add stamped, a change dropped). */
    Next,

    /**
     * The change waits for its item's add, queued after it: it is skipped until a step sends
     * something, then tried again; a pass that ends with changes waiting is tried again after the
     * wait.
     */
    Wait,

    /** It failed in a way that may pass: try again later. */
    Retry,

    /** The session has ended: everything waits for the next kick. */
    Stop,
}

/** One change at a time, by the rules of [MarkOutbox] and [MarksFlusher]. */
internal class MarkSteps(
    private val outbox: MarkOutbox,
    private val store: MarksStore,
    private val repository: MarksRepository,
    private val wall: Clock,
    private val memory: FlushMemory,
) {
    /** Takes [change], the first of [queue] that can go, one step further. */
    suspend fun step(change: MarkChange, queue: List<MarkChange>): FlushStep = when (change) {
        is MarkChange.AddHighlight -> when {
            change.attemptedAt == null -> stamp(change)
            memory.isFresh(change) -> sendAdd(change, queue)
            else -> adoptOrSend(change, queue)
        }
        is MarkChange.AddBookmark -> {
            val result = repository.addBookmark(change.bookId, change.fileId, change.cfi, change.title)
            if (result is ApiResult.Success) store.bindServerId(change.bookId, change.id, result.value.id)
            finish(change, result)
        }
        else -> existing(change, queue)
    }

    /** Stamped before its first send; sent once the outbox gives it back stamped. */
    private suspend fun stamp(add: MarkChange.AddHighlight): FlushStep {
        val stamped = add.copy(attemptedAt = wall.instant())
        memory.stamped(stamped)
        outbox.replace(add, stamped)
        return FlushStep.Next
    }

    /** A send of [add] may have left: the server's copy is taken if there is one. */
    private suspend fun adoptOrSend(add: MarkChange.AddHighlight, queue: List<MarkChange>): FlushStep =
        when (val found = repository.duplicateOf(add, store.held(add.bookId))) {
            is ApiResult.Failure -> finish(add, found, listing = true)
            is ApiResult.Success -> found.value?.let { serverId ->
                store.bindServerId(add.bookId, add.id, serverId)
                finish(add, found)
            } ?: sendAdd(add, queue)
        }

    /** Known not to be on the server: with a delete waiting, neither needs to go. */
    private suspend fun sendAdd(add: MarkChange.AddHighlight, queue: List<MarkChange>): FlushStep {
        val delete = queue.firstOrNull { it is MarkChange.DeleteHighlight && it.sameItem(add) }
        if (delete != null) {
            outbox.markSent(add)
            outbox.markSent(delete)
            return FlushStep.Next
        }
        memory.sending(add)
        val result = repository.addHighlight(add.bookId, add.highlight)
        if (result is ApiResult.Success) store.bindServerId(add.bookId, add.id, result.value.id)
        return finish(add, result)
    }

    /**
     * A change of an item the server should know. Without a server id, it waits for an add still
     * queued; otherwise the add was dropped, and so is the change, except a restore, which makes the
     * highlight again (its add was dropped with a delete that is now undone), and a bookmark's
     * delete (its add may have landed: see [deleteUnknown]).
     */
    private suspend fun existing(change: MarkChange, queue: List<MarkChange>): FlushStep {
        val held = store.held(change.bookId)
        val serverId = held.serverIdOf(change)
        return when {
            serverId != null -> send(change, serverId)
            queue.any { it.addsItemOf(change) } -> FlushStep.Wait
            change is MarkChange.RestoreHighlight -> {
                outbox.remake(change.bookId, change.id, restored = true)
                FlushStep.Next
            }
            change is MarkChange.DeleteBookmark -> deleteUnknown(change, held)
            else -> {
                outbox.markSent(change)
                FlushStep.Next
            }
        }
    }

    /**
     * A bookmark deleted here whose add was given up: its answer may have been lost after it
     * landed, and the bookmark would come back at the next refresh. The add is sent again (the
     * server gives back the one it holds at that place) to learn its id, then it is deleted.
     */
    private suspend fun deleteUnknown(change: MarkChange.DeleteBookmark, held: HeldBook): FlushStep {
        val bookmark = held.bookmarks.firstOrNull { it.id == change.id }
        val cfi = bookmark?.cfi
        val file = held.fileId
        if (bookmark == null || cfi == null || file == null) {
            outbox.markSent(change)
            return FlushStep.Next
        }
        val added = repository.addBookmark(change.bookId, file, cfi, bookmark.title)
        if (added !is ApiResult.Success) return finish(change, added)
        store.bindServerId(change.bookId, change.id, added.value.id)
        return send(change, added.value.id)
    }

    /**
     * A change answered 404 was deleted elsewhere. A note typed here is not lost: the highlight is
     * made again with the values shown, in one write ([MarkOutbox.remake]), so that edits made
     * while the request was on its way are in it. A colour alone (or a cleared note) is dropped,
     * and the highlight goes.
     */
    private suspend fun send(change: MarkChange, serverId: ServerMarkId): FlushStep {
        val result = repository.send(change, serverId)
        val gone = (result as? ApiResult.Failure)?.error == ApiError.NotFound
        if (!gone || change !is MarkChange.ChangeHighlight) return finish(change, result)
        outbox.remake(change.bookId, change.id, noteTyped = change.note is NoteEdit.Set)
        return FlushStep.Sent
    }

    /** [listing]: the answer is the duplicate check's listing, whose unclear failures count apart. */
    private suspend fun finish(change: MarkChange, result: ApiResult<*>, listing: Boolean = false): FlushStep =
        when (memory.verdict(change, result, listing)) {
            MarkVerdict.Done -> {
                outbox.markSent(change)
                FlushStep.Sent
            }
            MarkVerdict.Retry -> FlushStep.Retry
            MarkVerdict.Stop -> FlushStep.Stop
        }
}
