package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Fetches the files of one step of a format, and says in the registry how far it is.
 *
 * @param io where the disk is read and written.
 * @param freeBytes the room left where the downloads go.
 */
internal class FormatRun(
    private val states: RecordStates,
    private val files: DownloadFiles,
    private val sources: FormatSources,
    io: CoroutineDispatcher,
    freeBytes: () -> Long = files::freeBytes,
) {
    private val fileRun = FileRun(files, sources, io, freeBytes)

    /** How many files were put in place since the start. */
    val fetched: Int get() = fileRun.fetched

    /**
     * Runs [step] for [record]. The format is planned once per run of its book, the answer kept in [plans]:
     * a file already whole in its place, in the version planned, is not fetched again.
     */
    suspend fun run(
        bookId: BookId,
        record: FormatRecord,
        step: Step,
        plans: MutableMap<DownloadFormat, FormatPlan>,
    ): StepResult {
        val before = states.book(bookId)
        val plan = plans[record.format] ?: when (val answer = sources.plans.plan(bookId, record.format)) {
            is ApiResult.Failure -> return unplanned(bookId, record, answer.error)
            is ApiResult.Success -> answer.value.also {
                plans[record.format] = it
                // First: once the record says this version, a file in its place is taken to be of it.
                fileRun.dropStale(bookId, record.format, it.files, known(bookId, before))
                states.adopt(bookId, record, it)
            }
        }
        val batch = Batch(
            bookId = bookId,
            format = record.format,
            wanted = plan.files.filter { step.files == StepFiles.All || it.kind == FileKind.Epub },
            known = known(bookId, before),
            sizes = FormatSizes(
                files, bookId, record.format, countedFiles(before, record.format, plan.files), plan.expectedBytes,
            ),
            set = { state, pages -> states.progress(bookId, record, state, pages) },
        )
        val result = fileRun.run(batch)
        if (result == StepResult.Done && step.files == StepFiles.All) {
            states.complete(bookId, record, fileRun.received(batch))
        }
        return result
    }

    /** A server that cannot be reached is a cut; any other answer fails the format with its reason. */
    private suspend fun unplanned(bookId: BookId, record: FormatRecord, error: ApiError): StepResult {
        val cut = error == ApiError.ServerUnreachable
        states.change(bookId, record) {
            val state = if (cut) {
                it.waiting(WaitReason.NoNetwork)
            } else {
                FormatState.Failed(DownloadFailure.Server(error), it.state.received)
            }
            it.copy(state = state)
        }
        return if (cut) StepResult.Cut else StepResult.Failed
    }

    /** The kept files the records of the book speak of, each with the version its record says it is of. */
    private fun known(bookId: BookId, book: DownloadedBook?): KnownFiles = book?.formats.orEmpty()
        .flatMap { record -> record.files.map { files.file(bookId, record.format, it.name) to it.version } }
        .toSet()
}
