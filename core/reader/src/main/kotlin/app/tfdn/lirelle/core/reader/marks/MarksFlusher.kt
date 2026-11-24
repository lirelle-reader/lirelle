package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import java.time.Clock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Told that the [MarksFlusher] sent changes of a book, so that its lists are read again. */
fun interface MarksSent {
    /** Called in the flusher's scope, apart from the flush: a slow answer holds nothing back. */
    suspend fun sent(bookId: BookId)
}

/** A failure of one flush, or of one [MarksSent] call, stays there. */
private val Quiet = CoroutineExceptionHandler { _, _ -> }

/**
 * Sends the changes of bookmarks and highlights the [MarkOutbox] holds, oldest first, and binds the
 * server ids the answers give in the [MarksStore]. One flush at a time, for as long as [scope]
 * lives; nothing it does can fail [scope].
 *
 * Retries follow the 2a rules ([RetryPolicy]): no network, a server that fails or is busy, or one
 * asking to slow down, and the change stays, tried again after a growing wait, for as long as it
 * takes; changes after it wait too, so that the server sees them in order. A change of something
 * the server no longer has (404) is done. The session has ended: everything stays until the next
 * [kick]. Any other failure: tried [RetryPolicy.unclearAttempts] times, then dropped. A write that
 * fails ends the pass, tried again after the wait.
 *
 * **No duplicates.** An add is stamped ([MarkChange.AddHighlight.attemptedAt]) before its first
 * send, and sent only as the outbox gives it back stamped. The first stamp is kept: a stamped add
 * is never stamped again. An add that was already stamped when read may be on the server (an
 * answer lost): before sending it again, the flusher looks among the book's highlights for one
 * with the same place and text, made no earlier than two minutes before the stamp, that no other
 * item here is bound to, and binds it instead. When there is none and the add's delete is waiting,
 * neither goes; undoing that delete later makes the highlight again.
 *
 * **Conflicts.** A change answered 404 (deleted elsewhere) with a typed note makes the highlight
 * again with the values shown, so that the text is not lost; with a colour alone it is dropped and
 * the highlight goes from the store. A restore answered 404 is done: the highlight is already live,
 * or purged from the trash, and the next refresh shows which. A bookmark's delete whose add was
 * given up sends the add again (the server gives back the one it holds at that place) to learn its
 * id, then deletes it: a lost answer cannot bring it back.
 *
 * After a pass, each book whose changes went is told to [sent], so that a list read while they were
 * on their way cannot leave the old state shown.
 */
class MarksFlusher(
    private val outbox: MarkOutbox,
    store: MarksStore,
    repository: MarksRepository,
    private val scope: CoroutineScope,
    wall: Clock,
    private val policy: RetryPolicy = RetryPolicy(),
    private val sent: MarksSent = MarksSent {},
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val memory = FlushMemory(policy)
    private val steps = MarkSteps(outbox, store, repository, wall, memory)

    init {
        scope.launch(Quiet) {
            while (true) {
                kicks.receive()
                supervisorScope { launch(Quiet) { flush() } }
            }
        }
    }

    /**
     * Asks for a flush: at start-up, after a change, when the network may be back. During a flush
     * it does not start a second one: it cuts the current wait short, or has the queue read once
     * more when the flush ends.
     */
    fun kick() {
        kicks.trySend(Unit)
    }

    private suspend fun flush() {
        var wait = policy.firstWait
        while (sendAll()) {
            withTimeoutOrNull(wait) { kicks.receive() }
            wait = (wait * 2).coerceAtMost(policy.longestWait)
        }
    }

    /** True when something is left that is worth another attempt. */
    private suspend fun sendAll(): Boolean {
        val waiting = mutableSetOf<MarkChange>()
        val books = mutableSetOf<BookId>()
        try {
            var again: Boolean? = null
            while (again == null) again = next(waiting, books)
            return again
        } finally {
            books.forEach { book -> scope.launch(Quiet) { sent.sent(book) } }
        }
    }

    /**
     * One step: the queue read again, its first change that can go taken further. Null to go on;
     * otherwise whether something is left that is worth another attempt.
     */
    private suspend fun next(waiting: MutableSet<MarkChange>, books: MutableSet<BookId>): Boolean? {
        val queue = outbox.pending()
        memory.keepOnly(queue)
        // A change that waited for its add is tried again once something went, else at the next pass.
        val change = queue.firstOrNull { it !in waiting } ?: return waiting.isNotEmpty()
        return when (steps.step(change, queue)) {
            FlushStep.Sent -> {
                books += change.bookId
                waiting.clear()
                moved(queue)
            }
            FlushStep.Next -> moved(queue)
            FlushStep.Wait -> null.also { waiting += change }
            FlushStep.Retry -> retried(change)
            FlushStep.Stop -> false
        }
    }

    /**
     * After a step that changes the queue: null to go on; true when the queue is still [before] (a
     * write failed), so that the pass waits before trying again instead of spinning.
     */
    private suspend fun moved(before: List<MarkChange>): Boolean? = if (outbox.pending() == before) true else null

    /**
     * A retryable failure. If a merge cancelled [change] while it was being sent, it may have left:
     * reported as sent, the change that undoes it is queued, and the pass goes on.
     */
    private suspend fun retried(change: MarkChange): Boolean? {
        if (change in outbox.pending()) return true
        outbox.markSent(change)
        return null
    }
}
