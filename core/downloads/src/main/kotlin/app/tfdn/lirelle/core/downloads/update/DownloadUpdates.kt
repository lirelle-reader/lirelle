package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.updateQueued
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeping what is downloaded in step with the server: finding what changed or vanished, and fetching it. */
interface DownloadUpdates {
    /** Checks every kept book; unless [force], only when the last full pass is more than an hour old. */
    suspend fun check(force: Boolean = false)

    /** Checks one kept book; does nothing for a book that is not kept. */
    suspend fun checkBook(bookId: BookId)

    /** Starts the pending updates of [bookId]. */
    suspend fun update(bookId: BookId): AskRefusal?

    /** Starts every pending update. */
    suspend fun updateAll(): AskRefusal?
}

/**
 * What the updates look at besides the registry.
 *
 * @property online whether the device has a network, now.
 * @property canDownload whether the account signed in may download.
 */
internal class UpdateInputs(val online: () -> Boolean, val canDownload: Flow<Boolean>)

/** The updates, in the registry: the check writes them there, and asking for one queues it there. */
internal class RegistryDownloadUpdates(
    private val registry: DownloadRegistry,
    private val check: UpdateCheck,
    private val clock: CheckClock,
    private val inputs: UpdateInputs,
    private val now: () -> Instant,
) : DownloadUpdates {
    /** One full pass at a time: a second one asked meanwhile finds the hour moved. */
    private val pass = Mutex()

    override suspend fun check(force: Boolean) {
        if (!inputs.online()) return
        pass.withLock {
            if (!force && checkedLately()) return
            val canDownload = inputs.canDownload.first()
            val reached = registry.books.first().map { check.check(it.bookId, canDownload) }
            // A pass in which the server answered for no book was not one: the next occasion tries again.
            if (reached.isEmpty() || reached.any { it }) clock.mark(now())
        }
    }

    override suspend fun checkBook(bookId: BookId) {
        if (inputs.online()) check.check(bookId, inputs.canDownload.first())
    }

    override suspend fun update(bookId: BookId): AskRefusal? {
        if (!inputs.canDownload.first()) return AskRefusal.NotAllowed
        registry.update(bookId) { it?.withUpdatesQueued(now()) }
        return null
    }

    override suspend fun updateAll(): AskRefusal? {
        if (!inputs.canDownload.first()) return AskRefusal.NotAllowed
        registry.books.first().forEach { book -> registry.update(book.bookId) { it?.withUpdatesQueued(now()) } }
        return null
    }

    /** A pass dated after now (the device's clock was moved back) does not count. */
    private suspend fun checkedLately(): Boolean {
        val age = Duration.between(clock.last() ?: return false, now())
        return !age.isNegative && age <= BetweenPasses
    }

    private companion object {
        val BetweenPasses: Duration = Duration.ofHours(1)
    }
}

/**
 * The updates that wait for the user are asked, at [now]; a book to delete at close is not updated, nor one the
 * server no longer has.
 */
private fun DownloadedBook.withUpdatesQueued(now: Instant): DownloadedBook = if (deleteAtClose || gone) {
    this
} else {
    copy(formats = formats.map { if (it.awaitsUser) it.updateQueued(now) else it })
}

private val FormatRecord.awaitsUser: Boolean
    get() = !removing && state is FormatState.Complete && update != null && update.state == null
