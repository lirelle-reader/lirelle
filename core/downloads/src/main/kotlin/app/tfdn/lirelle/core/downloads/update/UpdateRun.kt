package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.Batch
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.FileRun
import app.tfdn.lirelle.core.downloads.engine.FormatPlan
import app.tfdn.lirelle.core.downloads.engine.FormatSizes
import app.tfdn.lirelle.core.downloads.engine.KnownFiles
import app.tfdn.lirelle.core.downloads.engine.PlanFor
import app.tfdn.lirelle.core.downloads.engine.QueueInputs
import app.tfdn.lirelle.core.downloads.engine.StepResult
import app.tfdn.lirelle.core.downloads.engine.countedFiles
import app.tfdn.lirelle.core.downloads.engine.received
import app.tfdn.lirelle.core.downloads.engine.updateWaitReason
import app.tfdn.lirelle.core.downloads.engine.waiting
import app.tfdn.lirelle.core.downloads.engine.whileTrue
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Fetches the update of a complete format: the files of the newer version that are not kept as they are,
 * every page of a text made of pages, into their places beside the kept ones, with the fetch, the resume,
 * the room and the progress of a download. The record stays complete, and what it keeps goes on being
 * served, until the swap.
 *
 * @param fileRun plans the formats, and fetches into the places of the updates.
 */
internal class UpdateRun(
    private val states: UpdateStates,
    private val files: DownloadFiles,
    private val fileRun: FileRun,
    private val inputs: QueueInputs,
    private val swap: UpdateSwap,
) {
    /** How many files were put beside the kept ones since the start. */
    val fetched: Int get() = fileRun.fetched

    /**
     * Fetches the update of [format], when it is still to fetch and may run now, for as long as it is wanted;
     * then what is whole takes its place, unless the book is open.
     */
    suspend fun step(bookId: BookId, format: DownloadFormat): StepResult {
        val record = states.pending(bookId, format)
            ?.takeIf { updateWaitReason(it, inputs.conditions.first()) == null }
            ?: return StepResult.Done
        val result = whileTrue(wanted(bookId, record)) { fetch(bookId, record) }
        // Outside what can be stopped: a swap is not left half way. It also removes what a fetch stopped late
        // left of an update that is no longer asked.
        swap.settle(bookId)
        return result ?: StepResult.Stopped
    }

    /**
     * Plans the format again: the version may have moved since the check, and the newest is the one fetched,
     * what was received of another dropped. What the newer version opens with, its manifest or its map, is
     * put beside the kept one by the plan, and waits there with the files.
     */
    private suspend fun fetch(bookId: BookId, record: FormatRecord): StepResult {
        val before = states.book(bookId)
        val plan = when (val answer = fileRun.plans.plan(bookId, record.format, PlanFor.Update)) {
            is ApiResult.Failure -> return unplanned(bookId, record, answer.error)
            is ApiResult.Success -> answer.value
        }
        if (plan.version == record.version) {
            // The server is back to what is kept.
            states.change(bookId, record) { null }
            return StepResult.Done
        }
        // First: once the update says this version, a file waiting in its place is taken to be of it.
        fileRun.dropStale(bookId, record.format, plan.files, waiting(bookId, before))
        states.adopt(bookId, record, plan)
        val batch = batch(bookId, record, plan, before)
        val result = fileRun.run(batch)
        if (result == StepResult.Done) states.set(bookId, record, FormatState.Complete(fileRun.received(batch)))
        return result
    }

    /**
     * The files of [plan] that are not kept in that version: those are to be whole beside the kept ones. One
     * already there counts when an update of the book, as it was [before], said it of that version.
     */
    private fun batch(bookId: BookId, record: FormatRecord, plan: FormatPlan, before: DownloadedBook?): Batch {
        val records = before?.formats.orEmpty()
        val kept = records.filter { it.state is FormatState.Complete }
            .flatMap { kept -> kept.files.map { files.file(bookId, kept.format, it.name) to it.version } }
            .toSet()
        val wanted = plan.files.filterNot { (files.file(bookId, record.format, it.name) to it.version) in kept }
        val counted = countedFiles(before, record.format, wanted)
        val sizes = FormatSizes(files.updates, bookId, record.format, counted, plan.expectedBytes)
        return Batch(bookId, record.format, wanted, waiting(bookId, before), sizes) { state, pages ->
            states.progress(bookId, record, state, pages)
        }
    }

    /** The files the updates of [book] say wait beside the kept ones, each with the version it is said of. */
    private fun waiting(bookId: BookId, book: DownloadedBook?): KnownFiles = book?.formats.orEmpty()
        .flatMap { other -> other.update?.files.orEmpty().map { other.format to it } }
        .mapTo(mutableSetOf()) { (format, file) -> files.updates.file(bookId, format, file.name) to file.version }

    /** A server that cannot be reached is a cut; any other answer fails the update with its reason. */
    private suspend fun unplanned(bookId: BookId, record: FormatRecord, error: ApiError): StepResult {
        val cut = error == ApiError.ServerUnreachable
        states.change(bookId, record) {
            val state = if (cut) {
                it.waiting(WaitReason.NoNetwork)
            } else {
                FormatState.Failed(DownloadFailure.Server(error), it.state?.received ?: 0)
            }
            it.copy(state = state)
        }
        return if (cut) StepResult.Cut else StepResult.Failed
    }

    /**
     * Whether the update of [record] is wanted: still asked as it was, allowed by the account and by the
     * network. What is fetched of it is stopped when it is no longer so.
     */
    private fun wanted(bookId: BookId, record: FormatRecord): Flow<Boolean> =
        combine(states.books, inputs.conditions, inputs.canDownload) { books, conditions, allowed ->
            val current = books.firstOrNull { it.bookId == bookId }?.pending(record.format)
            allowed && current?.askedAt == record.askedAt && updateWaitReason(current, conditions) == null
        }
}
