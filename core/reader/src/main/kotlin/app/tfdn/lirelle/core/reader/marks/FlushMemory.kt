package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy

/** What becomes of a change after an answer. [Done] covers both "sent" and "given up". */
internal enum class MarkVerdict { Done, Retry, Stop }

/**
 * What the [MarksFlusher] remembers between attempts: the failed attempts of the changes whose
 * failure was unclear, and the adds it stamped and has not sent yet. Only touched by the one flush
 * running.
 */
internal class FlushMemory(private val policy: RetryPolicy) {
    /** By change, or by [Listing] for the duplicate check of an add: each counts its own attempts. */
    private val unclear = mutableMapOf<Any, Int>()

    /** Stamped here and not sent since: no send of them can have left. */
    private val fresh = mutableSetOf<MarkChange.AddHighlight>()

    /** Forgets what is about changes no longer waiting. */
    fun keepOnly(queue: List<MarkChange>) {
        val waiting = queue.toSet()
        unclear.keys.retainAll { (if (it is Listing) it.add else it) in waiting }
        fresh.retainAll(waiting)
    }

    fun stamped(add: MarkChange.AddHighlight) {
        fresh += add
    }

    fun isFresh(add: MarkChange.AddHighlight) = add in fresh

    /** [add] is about to be sent: from now on, a send of it may have left. */
    fun sending(add: MarkChange.AddHighlight) {
        fresh -= add
    }

    /** The 2a rules: what can succeed later is retried for as long as it takes; an unclear failure a few times. */
    fun verdict(change: MarkChange, result: ApiResult<*>, listing: Boolean = false): MarkVerdict {
        val key: Any = if (listing && change is MarkChange.AddHighlight) Listing(change) else change
        val verdict = when ((result as? ApiResult.Failure)?.error) {
            null, ApiError.NotFound, is ApiError.Moved -> MarkVerdict.Done
            ApiError.ServerUnreachable, ApiError.ServerFailed, ApiError.TooManyAttempts -> MarkVerdict.Retry
            ApiError.SessionExpired, ApiError.DefaultPasswordMustChange -> MarkVerdict.Stop
            else -> {
                val attempts = (unclear[key] ?: 0) + 1
                unclear[key] = attempts
                if (attempts >= policy.unclearAttempts) MarkVerdict.Done else MarkVerdict.Retry
            }
        }
        if (verdict == MarkVerdict.Done) unclear -= key
        return verdict
    }

    /** The listing read to look for a copy of [add] on the server. */
    private data class Listing(val add: MarkChange.AddHighlight)
}
