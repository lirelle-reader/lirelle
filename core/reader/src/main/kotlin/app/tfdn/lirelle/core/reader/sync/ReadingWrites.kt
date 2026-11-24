package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** How many late writes are remembered. */
private const val Remembered = 16

/** A listening state captured this close to the time the server stored a reading write was made by that write. */
private val SameWrite = Duration.ofSeconds(5)

/**
 * What the reading of this device means for its listening. For a read-along book the server keeps text and
 * audio in step: every text or narration position it stores moves the book's listening state to the same
 * sentence, dated when it stored it, whenever the position itself was made. After a time without network,
 * a reading older than the listening that followed it would so drag the listening back.
 *
 * - **Reading goes first** ([waiting]): a listening position is not sent while a text or narration position
 *   waits, so that the server never moves the listening after the device's own position landed.
 * - **What the reading made of the listening is not someone else's** ([movedListeningBefore]): the state the
 *   server derived from a reading write that waited ([landedLate]) is recognised by its date, and the
 *   listening made after that reading is written on top of it.
 *
 * A reading sent as it is made is not remembered: the state it makes stands against the player's writes and
 * is offered, as one written elsewhere. In memory, for as long as its profile is open. Safe from any thread.
 */
@ProfileScoped
class ReadingWrites @Inject constructor(private val outbox: Outbox) {
    private class Late(val madeAt: Instant, val storedAt: Instant)

    private val late = ArrayDeque<Late>()

    /** Told each time a text or narration position left the outbox: what waited for it may go. */
    @Volatile
    var onSent: () -> Unit = {}

    /** Whether a text or narration position still waits to be sent. */
    suspend fun waiting(): Boolean = outbox.pending().let { it.positions.isNotEmpty() || it.narrations.isNotEmpty() }

    /** A position or narration made at [madeAt], by the device's clock, was sent after it waited: [result]. */
    fun landedLate(madeAt: Instant, result: ApiResult<SavedPosition>) {
        val saved = (result as? ApiResult.Success)?.value?.takeIf { it.outcome == SaveOutcome.Saved }
        val storedAt = saved?.storedAt ?: return
        synchronized(late) {
            late.addLast(Late(madeAt, storedAt))
            if (late.size > Remembered) late.removeFirst()
        }
    }

    /** A text or narration position left the outbox, sent or given up. */
    fun sent() = onSent()

    /**
     * Whether a listening state captured at [capturedAt], by the server's clock, is what the server made of
     * this device's late reading, all of it made before [madeAt] by the device's clock: a listening
     * position made at [madeAt] is the user's later word.
     */
    fun movedListeningBefore(capturedAt: Instant, madeAt: Instant): Boolean = synchronized(late) {
        val near = late.filter { Duration.between(it.storedAt, capturedAt).abs() <= SameWrite }
        near.isNotEmpty() && near.all { it.madeAt.isBefore(madeAt) }
    }
}
