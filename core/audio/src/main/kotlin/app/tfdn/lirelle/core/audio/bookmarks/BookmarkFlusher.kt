package app.tfdn.lirelle.core.audio.bookmarks

import app.tfdn.lirelle.core.audio.position.AfterFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.audio.position.UnclearFailures
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private enum class Verdict { Done, Retry, Stop }

/** A failure of one flush stays in that flush. */
private val Quiet = CoroutineExceptionHandler { _, _ -> }

/**
 * Sends the bookmark changes the [BookmarkQueue] holds, oldest first, and lays each answer into the book's
 * list. One flush at a time, for as long as [scope] lives; nothing it does can fail [scope].
 *
 * Retries follow the rules of the playback flusher ([RetryPolicy], [UnclearFailures]): no network, a server
 * that fails or is busy, or one asking to slow down, and the change stays, tried again after a growing wait,
 * for as long as it takes; the changes after it wait too, so that the server sees them in order. The session
 * has ended: everything stays until the next [kick]. Any other failure: tried [RetryPolicy.unclearAttempts]
 * times, then dropped. An edit or a delete of a bookmark the server no longer has is done, and the bookmark
 * leaves the list.
 *
 * An add carries the bookmark's own id as its client id, so a retry is never stored twice. It is stamped
 * before its first send: from then on a delete waits after it instead of cancelling it. An add the server
 * refuses because another bookmark is at that second is dropped with the changes of its bookmark, and the
 * book's list is read again so that the one there shows.
 */
class BookmarkFlusher(
    private val queue: BookmarkQueue,
    private val repository: AudioBookmarkRepository,
    scope: CoroutineScope,
    private val policy: RetryPolicy = RetryPolicy(),
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Held while changes are being sent, by a flush or by [sendNow]. */
    private val sending = Mutex()

    /** Only touched while [sending] is held. */
    private val failures = UnclearFailures<BookmarkChange>(policy)

    init {
        scope.launch(Quiet) {
            while (true) {
                kicks.receive()
                supervisorScope { launch(Quiet) { flush() } }
            }
        }
    }

    /**
     * Asks for a flush: at start-up, after a change, when the network may be back. During a flush it does
     * not start a second one: it cuts the current wait short, or has the queue read once more when it ends.
     */
    fun kick() {
        kicks.trySend(Unit)
    }

    /**
     * Sends [add] now when it is the first change waiting and nothing is being sent, and gives the server's
     * answer. Null when it was left to the flush: something waits before it, a flush runs, or it failed (it
     * stays, tried again by the flush).
     */
    suspend fun sendNow(add: BookmarkChange.Add): BookmarkCreateResult? {
        if (!sending.tryLock()) return null.also { kick() }
        try {
            val first = queue.pending().firstOrNull()
            if (first !is BookmarkChange.Add || first.id != add.id) return null.also { kick() }
            val (verdict, answer) = sendAdd(first)
            if (verdict != Verdict.Done) kick()
            return answer
        } finally {
            sending.unlock()
        }
    }

    /** Reads the book's list from the server and keeps it, unless an answer came in meanwhile. */
    suspend fun refresh(bookId: BookId) {
        val seen = queue.book(bookId).first().version
        val list = repository.list(bookId) as? ApiResult.Success ?: return
        queue.keepServer(bookId, list.value, seen)
    }

    private suspend fun flush() {
        var wait = policy.firstWait
        while (sending.withLock { pass() }) {
            withTimeoutOrNull(wait) { kicks.receive() }
            wait = (wait * 2).coerceAtMost(policy.longestWait)
        }
    }

    /** One pass over the queue: true when something is left that is worth another attempt. */
    private suspend fun pass(): Boolean {
        failures.retain(queue.pending().toSet())
        var verdict = Verdict.Done
        var last: BookmarkChange? = null
        var change = queue.pending().firstOrNull()
        while (verdict == Verdict.Done && change != null && change != last) {
            verdict = send(change)
            last = change
            change = queue.pending().firstOrNull()
        }
        // A change still first once it was done: the queue could not be written. Wait rather than spin.
        return verdict == Verdict.Retry || (verdict == Verdict.Done && change != null)
    }

    private suspend fun send(change: BookmarkChange): Verdict = when (change) {
        is BookmarkChange.Add -> sendAdd(change).first
        is BookmarkChange.Edit -> settle(
            change,
            repository.edit(change.bookId, change.id, change.title, change.note),
        ) { Settled.Holds(it) }
        is BookmarkChange.Delete -> settle(change, repository.delete(change.bookId, change.id)) {
            Settled.Gone(change.bookId, change.id)
        }
    }

    private suspend fun sendAdd(add: BookmarkChange.Add): Pair<Verdict, BookmarkCreateResult?> {
        // Cancelled meanwhile by a delete: nothing to send.
        val stamped = queue.stamp(add) ?: return Verdict.Done to null
        val result = repository.create(stamped.bookId, stamped.bookmark)
        if (result is ApiResult.Failure) return failed(stamped, result.error) to null
        val answer = (result as ApiResult.Success).value
        failures.forget(stamped)
        when (answer) {
            is BookmarkCreateResult.Created -> queue.settle(stamped, Settled.Holds(answer.bookmark))
            BookmarkCreateResult.SameSecond -> {
                queue.forget(stamped.bookId, stamped.id)
                refresh(stamped.bookId)
            }
        }
        return Verdict.Done to answer
    }

    private suspend fun <T> settle(
        change: BookmarkChange,
        result: ApiResult<T>,
        answer: (T) -> Settled,
    ): Verdict = when (result) {
        is ApiResult.Failure -> failed(change, result.error)
        is ApiResult.Success -> {
            failures.forget(change)
            queue.settle(change, answer(result.value))
            Verdict.Done
        }
    }

    private suspend fun failed(change: BookmarkChange, error: ApiError): Verdict =
        when (failures.verdict(change, error)) {
            AfterFailure.GiveUp -> {
                // An edit or a delete of a bookmark the server no longer has: it leaves the list too.
                val gone = error == ApiError.NotFound && change !is BookmarkChange.Add
                queue.settle(change, if (gone) Settled.Gone(change.bookId, change.id) else Settled.Unknown)
                Verdict.Done
            }
            AfterFailure.Retry -> Verdict.Retry
            AfterFailure.Stop -> Verdict.Stop
        }
}
