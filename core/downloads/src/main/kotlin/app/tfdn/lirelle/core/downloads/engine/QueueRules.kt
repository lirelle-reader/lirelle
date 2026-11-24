package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.library.net.NetworkState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What decides whether a download may run, besides its own record. */
internal data class Conditions(val network: NetworkState, val allowMetered: Boolean)

/** Which files of a format a step fetches: the narration's EPUB comes before the audio of the book. */
internal enum class StepFiles { All, EpubOnly }

/** @property update true when the step fetches the update of a complete format, beside what is kept. */
internal data class Step(val format: DownloadFormat, val files: StepFiles, val update: Boolean = false)

private val BackOffs = listOf(5.seconds, 30.seconds, 2.minutes)
private val LongestBackOff = 5.minutes

/** Still to fetch: queued, on its way or waiting, and not being deleted. */
internal val FormatRecord.isPending: Boolean
    get() = !removing && state !is FormatState.Complete && state !is FormatState.Failed

/** Not complete, failed or not, and not being deleted: what the queue shows. */
internal val FormatRecord.isUnfinished: Boolean get() = !removing && state !is FormatState.Complete

/** Null when [record] may run now; otherwise what it waits for. */
internal fun waitReason(record: FormatRecord, conditions: Conditions): WaitReason? =
    waitReason(record.anyNetwork, conditions)

/** Null when what the user let use any network, or not ([anyNetwork]), may run now; otherwise what it waits for. */
internal fun waitReason(anyNetwork: Boolean, conditions: Conditions): WaitReason? = when {
    !conditions.network.online -> WaitReason.NoNetwork
    conditions.network.unmetered || conditions.allowMetered || anyNetwork -> null
    else -> WaitReason.UnmeteredOnly
}

/**
 * What is still to fetch of [book], in order: the text, the narration's EPUB, the audiobook, the narration; then
 * the updates asked of what it keeps.
 */
internal fun steps(book: DownloadedBook): List<Step> {
    val pending = book.formats.filter { it.isPending }.mapTo(mutableSetOf()) { it.format }
    val updates = book.formats.filter { it.isUpdatePending }.map { it.format }.sorted()
    return listOf(
        Step(DownloadFormat.Text, StepFiles.All),
        Step(DownloadFormat.Narration, StepFiles.EpubOnly),
        Step(DownloadFormat.Audiobook, StepFiles.All),
        Step(DownloadFormat.Narration, StepFiles.All),
    ).filter { it.format in pending } + updates.map { Step(it, StepFiles.All, update = true) }
}

/**
 * The books with something not complete, or with an update asked and not fetched, in the order asked. Books asked
 * at the same time go by when they last had a format completed (`downloadedAt`, set again at each one), then as
 * [books] lists them, the registry's order, where a book goes last each time its record changes: an id says
 * nothing of when a book was asked.
 */
internal fun queueOrder(books: List<DownloadedBook>): List<DownloadedBook> = books
    .mapNotNull { book -> book.formats.filter { it.work != null }.minOfOrNull { it.askedAt }?.let { it to book } }
    .sortedWith(compareBy({ it.first }, { it.second.downloadedAt }))
    .map { it.second }

/** The first book in order with a format, or an update, that may run now. */
internal fun nextBook(books: List<DownloadedBook>, conditions: Conditions): DownloadedBook? =
    queueOrder(books).firstOrNull { book -> book.formats.any { mayRun(it, conditions) } }

/** How long after the [attempt]th cut in a row, counted from 1, a download is tried again. */
internal fun backOff(attempt: Int): Duration = BackOffs.getOrElse(attempt - 1) { LongestBackOff }

/** [this] with what cannot run now marked as waiting, with what it has received. */
internal fun DownloadedBook.waitingFor(conditions: Conditions): DownloadedBook = copy(
    formats = formats.map { record ->
        val reason = waitReason(record, conditions).takeIf { record.isPending }
        val forUpdate = updateWaitReason(record, conditions).takeIf { record.isUpdatePending }
        when {
            reason != null -> record.copy(state = record.waiting(reason))
            forUpdate != null -> record.copy(update = record.update?.let { it.copy(state = it.waiting(forUpdate)) })
            else -> record
        }
    },
)

/**
 * Whether the runner has something to do: a download or an update that may run, or one that does not say what
 * it waits for.
 */
internal fun hasWork(books: List<DownloadedBook>, conditions: Conditions): Boolean =
    books.any { it.waitingFor(conditions) != it } || nextBook(books, conditions) != null

/**
 * The state of a record that waits for [reason]. One that has not started has no size of its own yet: it says
 * that of its files once planned, else the one it was asked with, so that what is left is known before the
 * first byte.
 */
internal fun FormatRecord.waiting(reason: WaitReason): FormatState = when (val state = state) {
    is FormatState.Downloading -> FormatState.Waiting(reason, state.receivedBytes, state.totalBytes)
    is FormatState.Waiting -> state.copy(reason = reason, totalBytes = state.totalBytes ?: sizeBeforeStart)
    else -> FormatState.Waiting(reason, 0, sizeBeforeStart)
}

/** The size of the planned files when each has one, else the size announced when the format was asked. */
private val FormatRecord.sizeBeforeStart: Long?
    get() = files.map { it.expectedBytes }.takeIf { it.isNotEmpty() && null !in it }?.sumOf { it ?: 0 }
        ?: expectedBytes
