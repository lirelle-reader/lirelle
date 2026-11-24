package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.update.UpdateRun
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches what the queue holds, downloads and updates, one file at a time, following the rules of the queue.
 * Whoever calls it, the application while it is open or the system's job, there is one run at a time: no file
 * is fetched twice.
 */
class DownloadRunner internal constructor(
    private val registry: DownloadRegistry,
    private val inputs: QueueInputs,
    private val formats: FormatRun,
    private val updates: UpdateRun,
    private val queue: DownloadQueue,
    private val start: StartCheck,
    private val disposal: Disposal,
) {
    private val mutex = Mutex()

    /** How many callers of [halted] want nothing fetched. */
    private val halts = MutableStateFlow(0)

    /** The cuts in a row, without a file fetched between them. */
    private var cuts = 0

    /**
     * Fetches until nothing can be: the queue is empty, what is left failed, or it waits for a network it may
     * use. A download cut while the network is one it may use is tried again from here, after a while. If
     * another run is going on, waits for it first.
     */
    suspend fun runUntilIdle() = mutex.withLock {
        whileTrue(halts.map { it == 0 }) { fetchUntilIdle() }
        Unit
    }

    /**
     * Stops what is being fetched, or waited for, and runs [block] once it has ended: nothing is fetched, and
     * no file written, until [block] returns. For who deletes everything.
     */
    suspend fun <T> halted(block: suspend () -> T): T {
        halts.update { it + 1 }
        try {
            return mutex.withLock { block() }
        } finally {
            halts.update { it - 1 }
        }
    }

    private suspend fun fetchUntilIdle() {
        start.once()
        while (true) {
            if (!inputs.canDownload.first()) queue.cancelPending()
            val conditions = inputs.conditions.first()
            val book = nextBook(registry.books.first(), conditions) ?: break
            val before = formats.fetched + updates.fetched
            val cut = runBook(book.bookId)
            if (formats.fetched + updates.fetched != before) cuts = 0
            if (cut) pause()
        }
        sayWaiting()
    }

    /** Keeps the queue going for as long as the caller lives: runs whenever there is something to do. */
    suspend fun run(): Nothing = coroutineScope {
        launch { inputs.canDownload.collect { allowed -> if (!allowed) queue.cancelPending() } }
        follow()
    }

    private suspend fun follow(): Nothing {
        while (true) {
            runUntilIdle()
            combine(registry.books, inputs.conditions, ::hasWork).first { it }
        }
    }

    /** Runs the steps of one book in order. True when it was cut: the network is to be waited for. */
    private suspend fun runBook(bookId: BookId): Boolean {
        val plans = mutableMapOf<DownloadFormat, FormatPlan>()
        for (step in registry.book(bookId)?.let(::steps).orEmpty()) {
            val result = if (step.update) updates.step(bookId, step.format) else download(bookId, step, plans)
            when (result) {
                StepResult.Stopped -> return false
                StepResult.Cut -> return true
                else -> Unit
            }
        }
        return false
    }

    /**
     * Runs a step of a download, when its format is still to fetch and may run now, for as long as it is
     * wanted.
     */
    private suspend fun download(
        bookId: BookId,
        step: Step,
        plans: MutableMap<DownloadFormat, FormatPlan>,
    ): StepResult {
        val record = registry.book(bookId)?.pending(step.format)
            ?.takeIf { waitReason(it, inputs.conditions.first()) == null }
            ?: return StepResult.Done
        val result = whileTrue(wanted(bookId, record)) { formats.run(bookId, record, step, plans) }
        if (result == null) disposal.sweep(bookId, step.format)
        return result ?: StepResult.Stopped
    }

    /**
     * Whether [record] is wanted: still recorded as it was asked, allowed by the account and by the network.
     * What is fetched of it is cancelled when it stops being so, what it received kept.
     */
    private fun wanted(bookId: BookId, record: FormatRecord): Flow<Boolean> =
        combine(registry.books, inputs.conditions, inputs.canDownload) { books, conditions, allowed ->
            val current = books.firstOrNull { it.bookId == bookId }?.pending(record.format)
            allowed && current?.askedAt == record.askedAt && waitReason(current, conditions) == null
        }

    /** After a cut: until the back-off ends, the network changes, or what is queued does. */
    private suspend fun pause() {
        cuts++
        val changes = combine(registry.books, inputs.conditions) { books, conditions ->
            conditions to books.flatMap { it.pendingKeys() }
        }
        withTimeoutOrNull(backOff(cuts)) { changes.distinctUntilChanged().drop(1).first() }
    }

    /** What is left and cannot run says what it waits for. */
    private suspend fun sayWaiting() {
        val conditions = inputs.conditions.first()
        registry.books.first().filter { it.waitingFor(conditions) != it }.forEach { book ->
            registry.update(book.bookId) { it?.waitingFor(conditions) }
        }
    }
}

private fun DownloadedBook.pending(format: DownloadFormat): FormatRecord? =
    formats.firstOrNull { it.format == format && it.isPending }

/** What is still to fetch of the book: each format, and whether it is its update. */
private fun DownloadedBook.pendingKeys(): List<Triple<BookId, DownloadFormat, Boolean>> =
    formats.filter { it.isPending || it.isUpdatePending }.map { Triple(bookId, it.format, it.isUpdatePending) }
