package app.tfdn.lirelle.feature.player.controller

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long after a first failure the session is asked for again. */
internal val FirstRetryAfter = 200.milliseconds

/** The longest wait between two tries: each wait is twice the one before, up to this. */
internal val LongestRetryWait = 2.seconds

/**
 * How long after the first failure a failure is the last one: the session is then given up until someone needs
 * it again. It is looked at only when a try fails, so the tries end at the first failure this long after the
 * first one — with the waits above, about eleven seconds, a little more when the tries themselves take time.
 */
internal val GiveUpAfter = 10.seconds

/**
 * Tries again a connection to the playback service that was refused, failed or dropped: after
 * [FirstRetryAfter], then twice as long each time, up to [LongestRetryWait]; and gives up at the first failure
 * that comes [GiveUpAfter] or more after the first one (not at that moment exactly: no try is cut short),
 * until the next need starts anew ([reset]).
 *
 * It is for the moment after a change of profile: the service of the profile that closed may still be there,
 * and refuses the next profile's controller until it has ended; the service reached a little later is a new
 * one, made of the new profile's objects.
 *
 * Nothing is tried once [scope] has ended — the profile closed, and a session reached then would be held by
 * nobody who could release it — nor when nobody needs the session any more ([needed]).
 *
 * @param scope the connection's, on the main thread: the waits run in it, and end with it.
 * @param time tells how long the failures have lasted.
 * @param needed whether someone still wants the session: a screen watching a service that runs, a command
 *   waiting.
 * @param again connects once more, as part of the same series of tries.
 * @param givenUp told when it stops trying: what waited for the session waits no more.
 */
internal class ConnectionRetry(
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val needed: () -> Boolean,
    private val again: () -> Unit,
    private val givenUp: () -> Unit,
) {
    private var failingSince: TimeMark? = null
    private var wait = FirstRetryAfter
    private var waiting: Job? = null

    /** A new need, or a connection made: the next failure is a first one, and no try is waited for. */
    fun reset() {
        waiting?.cancel()
        waiting = null
        failingSince = null
        wait = FirstRetryAfter
    }

    /** A connection was refused or failed: tried again after a wait, or given up. */
    fun failed() {
        val since = failingSince ?: time.markNow().also { failingSince = it }
        if (!scope.isActive || !needed() || since.elapsedNow() >= GiveUpAfter) return stop()
        val pause = wait
        wait = minOf(wait * 2, LongestRetryWait)
        waiting = scope.launch {
            delay(pause)
            // Nobody may want it any more by now: a screen left, the service it showed ended.
            if (needed()) again() else stop()
        }
    }

    private fun stop() {
        reset()
        givenUp()
    }
}
