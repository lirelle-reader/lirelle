package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ChangeSignal
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.ReadingWrites
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Elapsed time, for when no clock is given. */
private val SystemMonotonicClock = MonotonicClock { System.nanoTime() / NanosPerMilli }
private const val NanosPerMilli = 1_000_000L

/** How many times in a row the book's files may change under a position before it is held back. */
private const val ManifestRounds = 3

/** [Hold]: the position stays, and is not tried again by this flusher until a newer one replaces it. */
private enum class Verdict { Done, Retry, Stop, Hold }

/**
 * A first write, the one resent once the server's state was read after a conflict, the one resent after a
 * second conflict and a second reading (the server moved in between), or the one creating a state reset.
 */
private enum class Attempt { First, OnServerState, OnServerStateAgain, AfterReset }

/** A failure of one flush stays in that flush. */
private val Quiet = CoroutineExceptionHandler { _, _ -> }

private fun LocalPlayback.write(base: String?) = PlaybackWrite(
    assetId = assetId,
    positionMs = positionMs,
    capturedAt = at,
    operationId = operationId,
    baseVersion = base,
    contentVersion = contentVersion,
)

/**
 * Sends what the [PlaybackOutbox] holds. One flush at a time, for as long as [scope] lives; nothing
 * it does can fail [scope].
 *
 * A write carries the position's own operation id, so a retry is never applied twice. It is made
 * over the position's base version; when this device's own writes moved the book past that
 * version meanwhile (a tick made while the one before was on its way), it goes on top of them
 * instead. A version written elsewhere is never skipped over. Versions are compared for equality only.
 *
 * - Saved: the position leaves the outbox and the version it got becomes the book's known one, unless
 *   another was made known while the write was on its way (an answer to an offer, a book opened): that
 *   one, learnt later, stays; not when it is the version the write was made over, which the write
 *   overwrote. [changes] is told once the outbox is drained after a position that settled
 *   ([settle]), and while playing at most every minute ([ChangeSignal]).
 * - Overtaken (a newer state stands): the server's state is read again.
 *   - It cannot be read: the position stays, and is tried again later with the same operation id.
 *   - None any more (reset elsewhere): the position creates it again.
 *   - At one of the places of our last writes, or captured no later than our last write the server
 *     acknowledged or may have stored (no answer once sent, a failing server; not a write whose
 *     connection could not be made, which never left; both kept in [store], so they hold after a
 *     restart), or, when none ever was, than the position itself: it is ours, or older, and the position
 *     is resent on top of it with the same operation id. A state without a date at another place is not.
 *   - Made by the server out of this device's own reading that waited, older than the position
 *     ([ReadingWrites]): the position is the user's later word, resent on top of it.
 *   - Written elsewhere but within 5 s of the position: followed, the position resent on it, no offer.
 *   - Otherwise someone else wrote since, or the state has no date: the position is dropped and
 *     [writes] hears of the state that stands; the player offers it.
 *   Resent on a state, the position is made over that state's version (none when it is created again).
 *   A write overtaken again on the resend (the server moved in between) reads the state once more and
 *   decides again; a third time drops the position. While a state written elsewhere stands unanswered
 *   ([writes]), the book's positions are not sent: the latest waits in the outbox.
 * - The book's files changed: [onManifestChanged] tells the player to reload, and the position is
 *   placed again by its book time in the new manifest, with a new operation id, and sent at once.
 *   Past the end of a shorter book it is dropped (at the end, the book would count as read),
 *   with nothing told to stand: the state on the server may be this device's own. After
 *   [ManifestRounds] placements in a row without a saved write it is held back.
 *   When the manifest cannot be read, the position stays and is tried again later.
 * - No network, a server that fails or is busy, or one asking to slow down: the position stays and
 *   is tried again with [policy], for as long as it takes.
 * - The book is gone: the position is dropped, so the queue cannot jam.
 * - The session has ended: everything stays and the flush stops until the next [kick].
 * - Any other failure does not say whether trying again can help: the position is tried
 *   [RetryPolicy.unclearAttempts] times, then dropped.
 *
 * No position is sent while a text or narration position of the reader waits ([reading]): they are tried
 * again like a failure, and at once when the reading has left.
 *
 * Once no position is left to send, the listening [sessions] are declared, with the same rules.
 */
class PlaybackFlusher(
    private val outbox: PlaybackOutbox,
    private val store: PlaybackStore,
    private val repository: AudioRepository,
    scope: CoroutineScope,
    private val changes: ReadingChanges,
    private val policy: RetryPolicy = RetryPolicy(),
    private val onManifestChanged: suspend (bookId: BookId) -> Unit = {},
) {
    /** What the minute between two tellings while playing is measured by. Set it once, before the first [kick]. */
    var clock: MonotonicClock = SystemMonotonicClock

    private val signal = ChangeSignal(changes, MonotonicClock { clock.nowMillis() })

    /**
     * The listening sessions, declared once no position waits: the server reads a session against the
     * position. Set it once, before the first [kick].
     */
    var sessions: ListeningSessionQueue? = null
        set(value) {
            field = value
            sessionSender = value?.let { SessionSender(it, repository, policy) }
        }

    private var sessionSender: SessionSender? = null

    /**
     * Who hears which writes the server took or may have taken, and which were dropped. Set it once,
     * before the first [kick].
     */
    var writes: PlaybackWriteEvents? = null

    /**
     * The finished readings from before, captured before the first position of each book goes. Set it
     * once, before the first [kick].
     */
    var baselines: ReadingBaselines? = null

    /**
     * The reading of this device, which goes first and whose mark on the listening is not someone else's
     * ([ReadingWrites]). Set it once, before the first [kick].
     */
    var reading: ReadingWrites? = null

    /** The version to send a position over: past our own writes, or the one its book was settled on. */
    private val baseFor: suspend (LocalPlayback) -> String? = { position ->
        val base = own.baseOf(position)
        val events = writes
        if (events == null) base else events.baseFor(position, base)
    }

    /**
     * The positions to send now: not those held back by the manifest's rounds, nor those of a book against
     * which a state written elsewhere stands, unanswered (they wait in the outbox, not sent). None while a text or
     * narration position waits to be sent ([behindReading]): the server moves the listening to each reading
     * it stores, so the listening goes last.
     */
    private val sendable: suspend (List<LocalPlayback>) -> List<LocalPlayback> = { positions ->
        behindReading = positions.isNotEmpty() && reading?.waiting() == true
        if (behindReading) emptyList() else positions.filterNot { it in held || writes?.holds(it.bookId) == true }
    }

    /** Whether the positions last read wait behind the reading. Only touched by the one flush running. */
    private var behindReading = false

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** A flush runs; a tick's kick then only has the queue read once more when it is over. */
    private val flushing = AtomicBoolean(false)
    private val ticked = AtomicBoolean(false)

    /** Only touched by the one flush running. */
    private val failures = PositionFailures(policy)
    private val held = mutableSetOf<LocalPlayback>()
    private val own = OwnWrites()
    private val judge = StateJudge(store, repository, own) { reading }

    init {
        scope.launch(Quiet) {
            while (true) {
                kicks.receive()
                flushing.set(true)
                try {
                    supervisorScope { launch(Quiet) { flush() } }
                } finally {
                    flushing.set(false)
                    // A tick queued a position after the queue was last read.
                    if (ticked.getAndSet(false)) kick()
                }
            }
        }
    }

    /**
     * Asks for a flush: at start-up, after an enqueue, when the network may be back. During a
     * flush it does not start a second one: it cuts the current wait short, or has the queue read
     * once more when the flush ends.
     *
     * Not [urgent] (a tick while playing, every 5 s): it starts a flush when none runs, but never cuts
     * short the wait after a failure, so that a failing server, or a book waiting for its baseline, is
     * tried again on the growing wait and not at every tick. What it queued is read at the next try, or
     * once the flush is over.
     */
    fun kick(urgent: Boolean = true) {
        if (!urgent && flushing.get()) ticked.set(true) else kicks.trySend(Unit)
    }

    /**
     * A position settled (pause, seek, chapter change, timer stop, end, leaving, background): the screens
     * are told once what waits is sent. Call it after the position is enqueued, if it was.
     */
    fun settle() {
        signal.settle()
        kick()
    }

    private suspend fun flush() {
        var wait = policy.firstWait
        while (true) {
            // Read before the outbox is: a settling asked later is told by the flush its kick starts.
            val asked = signal.asked()
            ticked.set(false)
            if (!sendAll()) {
                signal.drained(asked)
                return
            }
            withTimeoutOrNull(wait) { kicks.receive() }
            wait = (wait * 2).coerceAtMost(policy.longestWait)
        }
    }

    /** True when something is left that is worth another attempt. */
    private suspend fun sendAll(): Boolean {
        val positions = outbox.pending()
        failures.retain(positions.toSet())
        held.retainAll(positions.toSet())
        var again = false
        val toSend = sendable(positions)
        // Behind the reading: tried again like a failure, and as soon as it has left ([ReadingWrites.onSent]).
        var waiting = behindReading
        for (position in toSend) {
            // A book's position waits for its finished reading from before; the other books do not.
            val mayGo = baselines?.capture(position.bookId) != false
            if (!mayGo) {
                waiting = true
                continue
            }
            val base = baseFor(position)
            when (attempt(position, base, Attempt.First)) {
                Verdict.Done -> outbox.markSent(position)
                Verdict.Retry -> again = true
                Verdict.Stop -> return false
                Verdict.Hold -> held += position
            }
        }
        // A position waiting for its baseline does not hold back the sessions.
        return again || sessionSender?.sendAll() == true || waiting
    }

    private suspend fun attempt(position: LocalPlayback, base: String?, attempt: Attempt): Verdict {
        // Known as the write goes: another version known by the time it is answered was learnt meanwhile.
        val knownBefore = store.knownVersion(position.bookId)
        val result = repository.savePlaybackState(position.bookId, position.write(base))
        if (result is ApiResult.Failure) {
            // No answer once sent, or a failing server: the write may have been stored all the same. Not when
            // the connection could not even be made (no network): the request never left.
            val maybeStored = result.error == ApiError.ServerUnreachable || result.error == ApiError.ServerFailed
            if (maybeStored && result.mayHaveReachedServer) {
                own.uncertain(position)
                store.setLastWritten(position.bookId, position.at, WrittenPlace(position.assetId, position.positionMs))
                writes?.written(position, acknowledged = false)
            }
            return failures.verdict(position, result.error)
        }
        return when (val answer = (result as ApiResult.Success).value) {
            is PlaybackWriteResult.Saved -> {
                // A state created again is this write's whatever was learnt meanwhile; so is the state it was made
                // over, learnt meanwhile (an answer on it): this write overwrote it, its answer is the newer.
                val now = store.knownVersion(position.bookId)
                val learnt = attempt != Attempt.AfterReset && now != knownBefore && now != base
                saved(position, base, answer.state.version, learntMeanwhile = learnt)
                Verdict.Done
            }
            PlaybackWriteResult.Overtaken -> when (attempt) {
                Attempt.First -> recover(position, again = false)
                // The server moved between the reading and the resend: read once more, then no more.
                Attempt.OnServerState -> recover(position, again = true)
                else -> dropped(position)
            }
            PlaybackWriteResult.ContentChanged -> placeAgain(position)
        }
    }

    /**
     * After a conflict: resent on the server's state when that state is ours, or written elsewhere at the
     * same place as the position (followed without an offer, whether a screen is open or not); dropped
     * otherwise. [again]: the second reading, after the resend met a conflict too.
     */
    private suspend fun recover(position: LocalPlayback, again: Boolean): Verdict {
        val read = repository.playbackState(position.bookId)
        if (read is ApiResult.Failure) return failures.verdict(position, read.error)
        val state = (read as ApiResult.Success).value
        val onIt = state == null || judge.isOurs(position, state) || judge.isSamePlace(position, state)
        if (!onIt) return dropped(position, state)
        if (state == null) own.forget(position.bookId)
        val next = when {
            state == null -> Attempt.AfterReset
            again -> Attempt.OnServerStateAgain
            else -> Attempt.OnServerState
        }
        // Over the state that stands (the source writes on its version), or over none to create it again.
        return attempt(position, state?.version, next)
    }

    /**
     * Someone else's state stands: the player offers it. [server] is that state when it is at hand. With
     * [told] false, [writes] does not hear of it: the position is dropped because the book's files changed,
     * where the state read may well be this device's own.
     */
    private suspend fun dropped(position: LocalPlayback, server: PlaybackState? = null, told: Boolean = true): Verdict {
        failures.forget(position)
        writes?.takeIf { told }?.let { events ->
            val standing = server ?: (repository.playbackState(position.bookId) as? ApiResult.Success)?.value
            events.dropped(position, standing)
        }
        return Verdict.Done
    }

    /**
     * [position], written over [base], got [version], which becomes the known one; not with [learntMeanwhile],
     * when another version (not [base]) was made known while the write was on its way: that one, later, stays.
     */
    private suspend fun saved(position: LocalPlayback, base: String?, version: String, learntMeanwhile: Boolean) {
        failures.forget(position)
        own.saved(position, base, version)
        signal.saved()
        store.setLastWritten(position.bookId, position.at, WrittenPlace(position.assetId, position.positionMs))
        writes?.written(position, acknowledged = true)
        if (!learntMeanwhile) store.setKnownVersion(position.bookId, version)
    }

    /** The same place in the book's new set of files, sent at once when the manifest is new indeed. */
    private suspend fun placeAgain(position: LocalPlayback): Verdict {
        onManifestChanged(position.bookId)
        if (own.placedAgain(position.bookId) >= ManifestRounds) return Verdict.Hold
        val result = repository.manifest(position.bookId)
        if (result is ApiResult.Failure) return failures.verdict(position, result.error)
        val manifest = (result as ApiResult.Success).value
        val timeline = BookTimeline(manifest)
        // Only a book whose length is known can be shorter than the position.
        val fits = !timeline.durationsKnown || position.bookTimeMs < timeline.totalMs
        val placed = timeline.assetPositionOf(position.bookTimeMs)?.takeIf { fits }
            ?: return dropped(position, told = false)
        val moved = position.copy(
            assetId = placed.first.assetId,
            positionMs = placed.second,
            contentVersion = manifest.contentVersion,
            operationId = newOperationId(),
        )
        outbox.replace(position, moved)
        own.countPlacedAgain(position.bookId)
        // The same content version again would be refused again: that waits like a failure.
        if (moved.contentVersion != position.contentVersion) kick()
        return Verdict.Retry
    }
}

/** [UnclearFailures] for positions, as the flusher's verdicts: [Verdict.Done] covers both "sent" and "given up". */
private class PositionFailures(policy: RetryPolicy) {
    private val failures = UnclearFailures<LocalPlayback>(policy)

    fun retain(positions: Set<LocalPlayback>) = failures.retain(positions)

    fun forget(position: LocalPlayback) = failures.forget(position)

    fun verdict(position: LocalPlayback, error: ApiError): Verdict = when (failures.verdict(position, error)) {
        AfterFailure.GiveUp -> Verdict.Done
        AfterFailure.Retry -> Verdict.Retry
        AfterFailure.Stop -> Verdict.Stop
    }
}
