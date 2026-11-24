package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FormatPlan
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.FormatPlans
import app.tfdn.lirelle.core.downloads.engine.PlanFor
import app.tfdn.lirelle.core.downloads.engine.countedFiles
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult

/**
 * Compares what is kept of a book with what the server has of it now, without fetching a file: a complete
 * format whose version moved gets a pending update, and a book the server no longer answers for is gone.
 * An album kept in the version the server has is compared by that version alone: its pages are not counted
 * again, and a count the server refuses is no answer about the book, nor is a book whose files the server cannot
 * serve for now (Komga analyses its new file): the update is offered once it can, the file's version moved.
 * Asking the plan also refreshes the page kept with the book, as far as the kept copy still opens with it: its
 * manifest and its map are those of the files on the device, and stay.
 */
internal class UpdateCheck(
    private val registry: DownloadRegistry,
    private val plans: FormatPlans,
) {
    /**
     * Checks one book. Left alone: a book to delete at close, a format not complete or being deleted, and one
     * whose update was asked (in progress or failed). Without [canDownload] only whether the book is gone is
     * recorded.
     *
     * @return whether the server answered for the book: it has it, or says it has not.
     */
    suspend fun check(bookId: BookId, canDownload: Boolean): Boolean {
        val book = registry.book(bookId)?.takeUnless { it.deleteAtClose } ?: return false
        val answers = book.formats.filter { it.isCheckable }
            .associate { it.format to plans.plan(bookId, it.format, PlanFor.Check) }
        if (answers.isEmpty()) return false
        // On the record as it is now: it may have moved while the server was asked.
        registry.update(bookId) { current ->
            if (current == null || current.deleteAtClose) current else current.checked(answers, canDownload)
        }
        return answers.values.any { it.isAnswer }
    }
}

private typealias Answers = Map<DownloadFormat, ApiResult<FormatPlan>>

/** The server said what the format is made of, or that it has none: any other failure says nothing. */
private val ApiResult<FormatPlan>.isAnswer: Boolean
    get() = this is ApiResult.Success || (this is ApiResult.Failure && error == ApiError.NotFound)

/** Complete, staying, and with no update on its way or failed. */
private val FormatRecord.isCheckable: Boolean
    get() = !removing && state is FormatState.Complete && update?.state == null

/**
 * One answer is enough to know the book is there, whatever the others say: a book that lost a format answers
 * "not found" for that format only. Failures of another kind say nothing.
 * A book that is gone is offered no update.
 */
private fun DownloadedBook.checked(answers: Answers, canDownload: Boolean): DownloadedBook {
    val found = answers.values.any { it is ApiResult.Success }
    val missing = answers.values.any { it is ApiResult.Failure && it.error == ApiError.NotFound }
    val formats = formats.map { record ->
        val plan = (answers[record.format] as? ApiResult.Success)?.value
        if (plan == null || !canDownload || !record.isCheckable) record else record.comparedWith(plan, this)
    }
    val foundGone = missing && !found
    return copy(
        formats = if (foundGone) formats.map { it.withoutOffer() } else formats,
        gone = if (found) false else missing || gone,
    )
}

/** A book that is gone has nothing newer to fetch: an update that was not started is no longer offered. */
private fun FormatRecord.withoutOffer(): FormatRecord = if (update?.state == null) copy(update = null) else this

/** A format the server has as it is kept has no update; its text is named as the server says of its file now. */
private fun FormatRecord.comparedWith(plan: FormatPlan, book: DownloadedBook): FormatRecord =
    if (plan.version == version) {
        copy(update = null, textName = nameIn(plan) ?: textName)
    } else {
        copy(update = FormatUpdate(plan.version, plan.files, bytesToFetch(plan, book), textName = nameIn(plan)))
    }

/** What the text is named by in [plan], a newer version of it; nothing for another format, which has no such name. */
internal fun FormatRecord.nameIn(plan: FormatPlan): TextName? = plan.textName.takeIf { format == DownloadFormat.Text }

/**
 * The expected size of those of the files of [plan], a newer version, that are not kept as they are, a file
 * of unknown size counting for nothing; the EPUB a narration shares with the text is the text's to count.
 * The pages of a text say no size and are all fetched again: they count for what the plan says of them
 * together, the size of their file.
 */
internal fun FormatRecord.bytesToFetch(plan: FormatPlan, book: DownloadedBook?): Long {
    val kept = files.map { it.name to it.version }.toSet()
    val fresh = countedFiles(book, format, plan.files).filterNot { (it.name to it.version) in kept }
    val pages = fresh.any { it.kind == FileKind.Page }
    return if (pages) plan.expectedBytes ?: 0 else fresh.sumOf { it.expectedBytes ?: 0 }
}
