package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.ui.Loadable
import java.time.Duration
import java.time.Instant

// ---------- What the buttons of the reading actions show ----------

/**
 * What a reading action's button shows.
 *
 * @property fraction what the ring shows of the book, from 0 to 1.
 * @property narrated for Read only: the reader can narrate the book, which the icon says.
 * @property filled the button of the mode used last, drawn in the accent colour.
 */
internal data class ActionRingUi(
    val kind: ActionKind,
    val enabled: Boolean,
    val status: RingStatus,
    val fraction: Float,
    val narrated: Boolean = false,
    val filled: Boolean = false,
)

internal enum class ActionKind { Read, Listen }

/** What a button says under its name. */
internal sealed interface RingStatus {
    /** Nothing: where the listening is is not known, or the action opens the reader's narration. */
    data object None : RingStatus

    data object Start : RingStatus

    /** Started, with no percentage worth showing. */
    data object Resume : RingStatus

    data class ResumeAt(val percent: Int) : RingStatus

    /** Finished: the action starts the book over, and the ring is full. */
    data object Again : RingStatus

    /** The device does not have the format, and there is no network. */
    data object Offline : RingStatus

    /** The device does not have the format, and the book is gone from the server. */
    data object Unavailable : RingStatus
}

private const val WholePercent = 100f

/**
 * What Read says: a [finished] book is not gone on with, it is read again; one never started starts; one that
 * was started says how far the user is, when a reader can open it.
 */
internal fun readStatus(canRead: Boolean, finished: Boolean, percent: Int): RingStatus = when {
    finished -> RingStatus.Again
    percent <= 0 -> RingStatus.Start
    canRead -> RingStatus.ResumeAt(percent)
    else -> RingStatus.Resume
}

/**
 * What Listen says. It says nothing until where the listening is is known, nor when that cannot be had, nor
 * when there is no audio file to play ([playable] false): the reader then narrates the book, and its progress
 * is not known here. A position under one percent resumes, without a percentage of nought.
 */
internal fun listenStatus(playable: Boolean, listening: Loadable<PlaybackState?>): RingStatus {
    if (!playable || listening !is Loadable.Ready) return RingStatus.None
    val known = listening.value
    val percent = known?.percentage?.toInt() ?: 0
    return when {
        known?.completed == true -> RingStatus.Again
        known == null || (known.percentage <= 0.0 && known.positionMs <= 0) -> RingStatus.Start
        percent <= 0 -> RingStatus.Resume
        else -> RingStatus.ResumeAt(percent)
    }
}

/**
 * What a button that opens nothing says on a page built without the server, where it is the device not having
 * the format that switches it off: it waits for the network, or the book is [gone] from the server. On a page
 * the server gave ([kept] null) the status stays what it is.
 */
internal fun switchedOff(status: RingStatus, kept: Set<DownloadFormat>?, gone: Boolean): RingStatus = when {
    kept == null -> status
    gone -> RingStatus.Unavailable
    else -> RingStatus.Offline
}

/** How much of its ring a status fills: the percentage it names, all of it for a finished book, else nothing. */
internal fun ringFraction(status: RingStatus): Float = when (status) {
    RingStatus.Again -> 1f
    is RingStatus.ResumeAt -> (status.percent / WholePercent).coerceIn(0f, 1f)
    else -> 0f
}

/**
 * The description of a button, not yet knowing which one is filled.
 *
 * @param opens the action has something to open; when it has not, see [switchedOff].
 */
internal fun actionRing(
    kind: ActionKind,
    opens: Boolean,
    status: RingStatus,
    kept: Set<DownloadFormat>?,
    gone: Boolean = false,
): ActionRingUi {
    val shown = if (opens) status else switchedOff(status, kept, gone)
    return ActionRingUi(kind, enabled = opens, status = shown, fraction = ringFraction(shown))
}

/** The button while it cannot be used yet, where the listening is still being loaded: it says what it said. */
internal fun ActionRingUi.waiting(): ActionRingUi = copy(enabled = false)

/**
 * A listening position dated this close after a reading position was made by that reading: for a synchronised
 * book the server moves the listening each time it stores a reading position. The same five seconds as
 * `SameWrite` in `core/reader`'s `ReadingWrites.kt`, which is private to that module.
 */
private val SameWrite = Duration.ofSeconds(5)

/** Whether the status is one of a book that was started or finished. */
private val RingStatus.begun: Boolean
    get() = this == RingStatus.Resume || this is RingStatus.ResumeAt || this == RingStatus.Again

/**
 * Which button is filled: the one of the mode used last, by the dates of the reading and of the listening
 * positions. Listen is, when it has a date and:
 * - the reading has one, earlier by more than [SameWrite]: a listening dated closer after the reading comes
 *   from that reading, and Read is filled;
 * - or the reading has none and Read says the book was never read. A reading started or finished whose date
 *   is not known, as on a page built without the server, does not lose to a dated listening.
 *
 * Otherwise it is Read, or Listen for a book with no text. A listening position not loaded yet has no date. A
 * switched-off button is never filled: the other one is, if it can be used.
 *
 * @param readingArrived the reading of the book is known. Until it is, the dates are not compared and Read
 *   stays filled: the listening often arrives first, and the fill must not go to Listen and come back.
 * @return null when no button can be used.
 */
internal fun filledKind(
    read: ActionRingUi?,
    listen: ActionRingUi?,
    readAt: Instant?,
    listenAt: Instant?,
    readingArrived: Boolean = true,
): ActionKind? {
    val listenedLast = when {
        listenAt == null || !readingArrived -> false
        readAt == null -> read?.status?.begun != true
        else -> Duration.between(readAt, listenAt) > SameWrite
    }
    val byDate = if (listenedLast) listOf(listen, read) else listOf(read, listen)
    return byDate.firstOrNull { it?.enabled == true }?.kind
}
