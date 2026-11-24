package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ChangeSignal
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How the flusher insists.
 *
 * @property firstWait the wait before the second attempt; it doubles after each failed one.
 * @property longestWait where the wait stops growing.
 * @property unclearAttempts how many times an item is tried when the failure does not say whether
 * trying again can help, before it is given up.
 */
data class RetryPolicy(
    val firstWait: Duration = 5.seconds,
    val longestWait: Duration = 5.minutes,
    val unclearAttempts: Int = 6,
)

/**
 * A position older than this when it is sent has waited (no network, app closed): it is sent with
 * its time, so that the server sets it aside when another device wrote a newer one meanwhile.
 */
private val LateAfter = 1.minutes

private enum class Verdict { Done, Retry, Stop }

private const val NanosPerMilli = 1_000_000L

/** A failure of one flush stays in that flush. */
private val Quiet = CoroutineExceptionHandler { _, _ -> }

/**
 * When the server says it stored this device's last write of each file, by the server's clock, and
 * the time a late write is sent with. One for the text positions, one for the narrations: the server
 * keeps their times apart.
 */
private class Stamps(private val wall: Clock) {
    private val stored = mutableMapOf<FileId, Instant>()

    /**
     * The time of a write made [at] when it is a late one, null when it is fresh: just after this
     * device's last write for the file when the device's clock puts it before.
     */
    fun editedAt(fileId: FileId, at: Instant): Instant? {
        val late = (wall.instant().toEpochMilli() - at.toEpochMilli()).milliseconds > LateAfter
        if (!late) return null
        val floor = stored[fileId]?.plusMillis(1) ?: return at
        return maxOf(at, floor)
    }

    /** Only a write of ours that landed: the time of one the server kept is somebody else's. */
    fun remember(fileId: FileId, result: ApiResult<SavedPosition>) {
        val saved = (result as? ApiResult.Success)?.value?.takeIf { it.outcome == SaveOutcome.Saved }
        saved?.storedAt?.let { stored[fileId] = it }
    }
}

/**
 * Sends what the [Outbox] holds: the positions first, then the narrations, then the sessions, so that
 * the server has a book's finish on record when it reads the session. One flush at a time, for as
 * long as [scope] lives; nothing it does can fail [scope].
 *
 * A position sent soon after it was made carries no time and always lands: the server compares the
 * time with its own clock, and a device whose clock is a little behind must not have the page the
 * user is on refused. One that waited longer than [LateAfter] carries its time, and the server sets
 * it aside when it holds a newer one; that counts as sent. That time is never before the one the
 * server gave this device's last write for the file: with a device clock behind the server's, a
 * late position would otherwise be set aside by the very position it follows. A narration is sent
 * by the same rule, against the times of the narrations; a session does not wait for it.
 *
 * - No network, a server that fails or is busy, or one asking to slow down: the item stays and is
 *   tried again later, for as long as it takes.
 * - The file is gone: the item is dropped, so that the queue cannot jam.
 * - The session has ended: everything stays and the flush stops until the next [kick].
 * Each position and session the server takes is told to [changes]: the screens that show the reading load
 * it again. An item that failed or was given up tells nothing. Narrations are written every 5 s while one
 * plays: they are told as an audiobook's positions are ([ChangeSignal], timed by [monotonic]), at most once
 * a minute while they keep coming, and once the outbox is drained after one that settled
 * ([settleNarration]).
 *
 * - Any other failure does not say whether trying again can help (a refused item, an answer that
 *   cannot be read): the item is tried [RetryPolicy.unclearAttempts] times, then dropped.
 *
 * [reading] hears of each position and narration that left the outbox, and of those the server stored after
 * they waited: the listening positions wait behind them ([ReadingWrites]).
 */
class OutboxFlusher(
    private val outbox: Outbox,
    private val repository: ReaderRepository,
    scope: CoroutineScope,
    wall: Clock,
    private val changes: ReadingChanges,
    private val policy: RetryPolicy = RetryPolicy(),
    monotonic: MonotonicClock = MonotonicClock { System.nanoTime() / NanosPerMilli },
    private val reading: ReadingWrites? = null,
) {
    private val narrationSignal = ChangeSignal(changes, monotonic)

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Failed attempts of the items whose failure was unclear. Only touched by the one flush running. */
    private val unclear = mutableMapOf<Any, Int>()

    private val positionStamps = Stamps(wall)
    private val narrationStamps = Stamps(wall)

    init {
        scope.launch(Quiet) {
            while (true) {
                kicks.receive()
                supervisorScope { launch(Quiet) { flush() } }
            }
        }
    }

    /**
     * Asks for a flush: at start-up, after an enqueue, when the network may be back. During a
     * flush it does not start a second one: it cuts the current wait short, or has the queue read
     * once more when the flush ends.
     */
    fun kick() {
        kicks.trySend(Unit)
    }

    /**
     * A narration settled (pause, jump, section change, end, stop, leaving): the screens are told once what
     * waits is sent. Call it after the narration is enqueued, if it was.
     */
    fun settleNarration() {
        narrationSignal.settle()
        kick()
    }

    private suspend fun flush() {
        var wait = policy.firstWait
        while (true) {
            // Read before the outbox is: a settling asked later is told by the flush its kick starts.
            val asked = narrationSignal.asked()
            if (!sendAll()) {
                narrationSignal.drained(asked)
                return
            }
            withTimeoutOrNull(wait) { kicks.receive() }
            wait = (wait * 2).coerceAtMost(policy.longestWait)
        }
    }

    /** True when something is left that is worth another attempt. */
    private suspend fun sendAll(): Boolean {
        val content = outbox.pending()
        // What is remembered of an item that no longer waits is of no use.
        unclear.keys.retainAll(content.positions.toSet<Any>() + content.narrations + content.sessions)
        val waiting = mutableSetOf<FileId>()
        for (position in content.positions) {
            when (send(position)) {
                Verdict.Done -> Unit
                Verdict.Retry -> waiting += position.fileId
                Verdict.Stop -> return false
            }
        }
        var again = waiting.isNotEmpty()
        for (narration in content.narrations) {
            when (send(narration)) {
                Verdict.Done -> Unit
                Verdict.Retry -> again = true
                Verdict.Stop -> return false
            }
        }
        // A session waits for its file's position: the server reads the session against it.
        for (pending in content.sessions.filterNot { it.fileId in waiting }) {
            when (send(pending)) {
                Verdict.Done -> Unit
                Verdict.Retry -> again = true
                Verdict.Stop -> return false
            }
        }
        return again
    }

    private suspend fun send(position: LocalPosition): Verdict {
        val editedAt = positionStamps.editedAt(position.fileId, position.at)
        val result =
            repository.savePosition(position.fileId, position.cfi, position.percent, editedAt, position.page)
        positionStamps.remember(position.fileId, result)
        if (editedAt != null) reading?.landedLate(position.at, result)
        return verdict(position, result).also {
            if (it == Verdict.Done) outbox.markSent(position)
            if (it == Verdict.Done) reading?.sent()
            // Stored or set aside for a newer one: either way the server may hold another than the screens show.
            if (result is ApiResult.Success) changes.changed()
        }
    }

    private suspend fun send(narration: LocalNarration): Verdict {
        val editedAt = narrationStamps.editedAt(narration.fileId, narration.at)
        val result = repository.saveNarration(narration.fileId, narration.place, narration.percent, editedAt)
        narrationStamps.remember(narration.fileId, result)
        if (editedAt != null) reading?.landedLate(narration.at, result)
        return verdict(narration, result).also {
            if (it == Verdict.Done) outbox.markSent(narration)
            if (it == Verdict.Done) reading?.sent()
            if (result is ApiResult.Success) narrationSignal.saved()
        }
    }

    private suspend fun send(pending: PendingSession): Verdict {
        val result = repository.saveSession(pending.fileId, pending.session)
        return verdict(pending, result).also {
            if (it == Verdict.Done) outbox.markSent(pending)
            if (result is ApiResult.Success) changes.changed()
        }
    }

    /** [Verdict.Done] covers both "sent" (a superseded position included) and "given up". */
    private fun verdict(item: Any, result: ApiResult<*>): Verdict {
        val verdict = when ((result as? ApiResult.Failure)?.error) {
            null, ApiError.NotFound, is ApiError.Moved -> Verdict.Done
            ApiError.ServerUnreachable, ApiError.ServerFailed, ApiError.TooManyAttempts -> Verdict.Retry
            ApiError.SessionExpired, ApiError.DefaultPasswordMustChange -> Verdict.Stop
            else -> {
                val attempts = (unclear[item] ?: 0) + 1
                unclear[item] = attempts
                if (attempts >= policy.unclearAttempts) Verdict.Done else Verdict.Retry
            }
        }
        if (verdict == Verdict.Done) unclear -= item
        return verdict
    }
}
