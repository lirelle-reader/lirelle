package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.engine.isPending
import app.tfdn.lirelle.core.downloads.engine.isUpdateFailed
import app.tfdn.lirelle.core.downloads.engine.isUpdatePending
import app.tfdn.lirelle.core.downloads.engine.isUpdateWhole
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId

/**
 * What ended since the queue was last empty.
 *
 * @property books the books of which a format, or the update of one, was fetched whole.
 * @property bytes what those took.
 * @property failed the books left with a failure.
 */
data class Batch(val books: Int, val bytes: Long, val failed: Int)

/** Nothing of [this] is still to fetch, download or update: what failed waits for the user. */
internal fun List<DownloadedBook>.nothingLeft(): Boolean =
    none { book -> book.formats.any { it.isPending || it.isUpdatePending } }

/**
 * Counts a batch by following the records: a format, or an update, counts when it is seen to end. It knows
 * nothing of what ended before the first records it is given, and is not kept across a restart.
 */
internal class BatchCount {
    private var before: Map<Pair<BookId, DownloadFormat>, FormatRecord>? = null
    private val whole = mutableSetOf<BookId>()
    private val failed = mutableSetOf<BookId>()
    private var bytes = 0L

    /** The records as they are now. */
    fun follow(books: List<DownloadedBook>) {
        val now = books.flatMap { book -> book.formats.map { (book.bookId to it.format) to it } }.toMap()
        before?.let { was ->
            now.forEach { (key, record) -> was[key]?.let { count(key.first, it, record) } }
        }
        before = now
    }

    /**
     * The queue is empty: the batch that ends, null when nothing ended in it but cancellations. The next one
     * starts.
     */
    fun take(): Batch? {
        val failing = before.orEmpty().filterValues { it.failed }.keys.map { it.first }
        val stillFailed = failed.count { it in failing }
        val batch = Batch(whole.size, bytes, stillFailed).takeIf { whole.isNotEmpty() || stillFailed > 0 }
        whole.clear()
        failed.clear()
        bytes = 0
        return batch
    }

    private fun count(bookId: BookId, was: FormatRecord, now: FormatRecord) {
        fetched(was, now)?.let {
            whole += bookId
            bytes += it
        }
        if (now.failed && !was.failed) failed += bookId
    }
}

private val FormatRecord.failed: Boolean get() = !removing && (state is FormatState.Failed || isUpdateFailed)

/**
 * What ended whole between [was] and [now], in bytes: the download of the format, or its update, which either
 * waits beside the kept copy for the book to close or has already taken its place. Null when nothing did.
 */
private fun fetched(was: FormatRecord, now: FormatRecord): Long? {
    val complete = now.state as? FormatState.Complete
    return when {
        complete == null || now.removing -> null
        was.state !is FormatState.Complete -> complete.bytes
        !was.isUpdatePending -> null
        now.isUpdateWhole -> (now.update?.state as? FormatState.Complete)?.bytes
        // Swapped in: an update that went and left the kept version, taken back or not needed, fetched nothing.
        now.update == null && now.version != was.version -> was.update?.bytes
        else -> null
    }
}
