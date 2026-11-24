package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.reader.marks.MarkChange.AddBookmark
import app.tfdn.lirelle.core.reader.marks.MarkChange.AddHighlight
import app.tfdn.lirelle.core.reader.marks.MarkChange.ChangeHighlight
import app.tfdn.lirelle.core.reader.marks.MarkChange.DeleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkChange.DeleteHighlight
import app.tfdn.lirelle.core.reader.marks.MarkChange.RestoreHighlight

/** How many cancelled changes are remembered, in case the flusher was sending one of them. */
private const val KeptCancelled = 32

/**
 * The changes waiting, oldest first, those of one item merged (see [MarkOutbox]).
 *
 * [cancelled]: changes a merge removed with the one that undid them (an add and its delete, a
 * delete and its restore). The flusher may have been sending one when it went; when it reports it
 * as sent (or its answer lost), the server may hold what the merge assumed it never would, and the
 * change that undoes it goes first in the queue. An add that may have been sent is stamped, and a
 * stamped add is never merged into or cancelled.
 */
internal data class MarkQueue(
    val changes: List<MarkChange> = emptyList(),
    val cancelled: List<MarkChange> = emptyList(),
) {
    fun enqueued(change: MarkChange): MarkQueue {
        val last = changes.lastOrNull { it.sameItem(change) }
        return when (change) {
            is AddHighlight -> plus(change)
            is AddBookmark -> if (last is DeleteBookmark) copy(changes = changes - last + change) else plus(change)
            is ChangeHighlight -> changed(change, last)
            is DeleteHighlight -> deleted(change)
            is RestoreHighlight -> restored(change, last)
            is DeleteBookmark -> if (last is AddBookmark) cancel(last) else plus(change)
        }
    }

    /** A change cancelled while it was being sent may be on the server: what undoes it goes first. */
    fun sent(change: MarkChange): MarkQueue {
        val gone = cancelled.firstOrNull { it.sameKind(change) }
        return when {
            change in changes -> copy(changes = changes - change)
            gone != null -> copy(changes = listOfNotNull(gone.undo()) + changes, cancelled = cancelled - gone)
            else -> this
        }
    }

    fun replaced(old: MarkChange, new: MarkChange): MarkQueue = if (old in changes) swap(old, new) else this

    private fun changed(change: ChangeHighlight, last: MarkChange?) = when {
        last is AddHighlight && last.attemptedAt == null -> swap(last, last.with(change))
        last is ChangeHighlight -> swap(last, last.then(change))
        last is DeleteHighlight -> this
        else -> plus(change)
    }

    /** The deletion wins over every change of the item waiting. */
    private fun deleted(change: DeleteHighlight): MarkQueue {
        val trimmed = copy(changes = changes.filterNot { it is ChangeHighlight && it.sameItem(change) })
        return when (val last = trimmed.changes.lastOrNull { it.sameItem(change) }) {
            is AddHighlight -> if (last.attemptedAt == null) trimmed.cancel(last) else trimmed.plus(change)
            is RestoreHighlight -> trimmed.cancel(last)
            is DeleteHighlight -> trimmed
            else -> trimmed.plus(change)
        }
    }

    /** An add a delete cancelled comes back. */
    private fun restored(change: RestoreHighlight, last: MarkChange?): MarkQueue {
        val add = cancelled.lastOrNull { it is AddHighlight && it.sameItem(change) }
        return when {
            last is DeleteHighlight -> cancel(last)
            last == null && add != null -> copy(changes = changes + add, cancelled = cancelled - add)
            else -> plus(change)
        }
    }

    private fun swap(old: MarkChange, new: MarkChange): MarkQueue {
        val at = changes.indexOf(old)
        return copy(changes = changes.subList(0, at) + new + changes.subList(at + 1, changes.size))
    }

    private fun cancel(change: MarkChange) =
        copy(changes = changes - change, cancelled = (cancelled + change).takeLast(KeptCancelled))

    private fun plus(change: MarkChange) = copy(changes = changes + change)
}
