package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.sync.RetryPolicy

/** What becomes of something whose sending failed. */
internal enum class AfterFailure {
    /** Given up: what it was about is gone, or trying again cannot help. */
    GiveUp,

    /** Tried again later. */
    Retry,

    /** The session ended: nothing more is sent until the next kick. */
    Stop,
}

/**
 * The verdict on a failed send, the same for positions and listening sessions: what the server no longer
 * has is given up, no network or a failing or busy server is tried again, an ended session stops, and a
 * failure that does not say whether trying again can help is given up after [RetryPolicy.unclearAttempts]
 * attempts of the same [K]. Only touched by the one flush running.
 */
internal class UnclearFailures<K>(private val policy: RetryPolicy) {
    private val unclear = mutableMapOf<K, Int>()

    fun retain(keys: Set<K>) = unclear.keys.retainAll(keys)

    fun forget(key: K) {
        unclear -= key
    }

    fun verdict(key: K, error: ApiError): AfterFailure {
        val verdict = when (error) {
            ApiError.NotFound, is ApiError.Moved -> AfterFailure.GiveUp
            ApiError.ServerUnreachable, ApiError.ServerFailed, ApiError.TooManyAttempts -> AfterFailure.Retry
            ApiError.SessionExpired, ApiError.DefaultPasswordMustChange -> AfterFailure.Stop
            else -> {
                val attempts = (unclear[key] ?: 0) + 1
                unclear[key] = attempts
                if (attempts >= policy.unclearAttempts) AfterFailure.GiveUp else AfterFailure.Retry
            }
        }
        if (verdict == AfterFailure.GiveUp) unclear -= key
        return verdict
    }
}
