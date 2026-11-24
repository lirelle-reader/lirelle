package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.update.UpdateCancel
import app.tfdn.lirelle.core.library.net.NetworkState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the queue and its runner look at besides the registry.
 *
 * @property canDownload whether the account signed in may download.
 */
internal class QueueInputs(
    network: Flow<NetworkState>,
    allowMetered: Flow<Boolean>,
    val canDownload: Flow<Boolean>,
) {
    val conditions: Flow<Conditions> = combine(network, allowMetered, ::Conditions).distinctUntilChanged()
}

/**
 * The queue: what was asked, in the registry, which is the only place that says where each download is. It
 * fetches nothing itself: the [DownloadRunner] does, following the registry.
 */
class DownloadQueue internal constructor(
    private val registry: DownloadRegistry,
    private val disposal: Disposal,
    private val updates: UpdateCancel,
    private val canDownload: Flow<Boolean>,
    private val now: () -> Instant,
) : Downloads {
    override val books: Flow<List<DownloadedBook>> = registry.books.map { it.visible() }.distinctUntilChanged()

    override val queue: Flow<QueueState> = registry.books.map(::queueState).distinctUntilChanged()

    override fun book(bookId: BookId): Flow<DownloadedBook?> =
        registry.books.map { books -> books.firstOrNull { it.bookId == bookId }?.visible() }.distinctUntilChanged()

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? =
        ask(bookId, formats, emptyMap())

    override suspend fun ask(
        bookId: BookId,
        formats: Set<DownloadFormat>,
        sizes: Map<DownloadFormat, Long>,
    ): AskRefusal? = ask(bookId, formats, sizes, TextName.Text)

    override suspend fun ask(
        bookId: BookId,
        formats: Set<DownloadFormat>,
        sizes: Map<DownloadFormat, Long>,
        textName: TextName,
    ): AskRefusal? {
        if (!canDownload.first()) return AskRefusal.NotAllowed
        if (formats.isNotEmpty()) {
            registry.update(bookId) { asked(it, bookId, formats, now(), Known(sizes, textName)) }
        }
        return null
    }

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) {
        val recorded = registry.book(bookId)?.formats.orEmpty()
        val narrationStays = recorded.any { it.format == DownloadFormat.Narration && !it.isUnfinished }
        disposal.drop(bookId) { record ->
            val named = format == null || record.format == format ||
                (format == DownloadFormat.Text && record.format == DownloadFormat.Narration)
            named && record.isUnfinished && !(record.format == DownloadFormat.Text && narrationStays)
        }
        updates.cancel(bookId, format)
    }

    override suspend fun retry(bookId: BookId) = registry.update(bookId) { book ->
        book?.copy(
            formats = book.formats.map {
                when {
                    it.isFailed -> it.queuedAgain(now())
                    it.isUpdateFailed -> it.updateQueued(now())
                    else -> it
                }
            },
        )
    }

    override suspend fun allowAnyNetworkNow() = registry.books.first().forEach { book ->
        registry.update(book.bookId) { current -> current?.copy(formats = current.formats.map { it.onAnyNetwork() }) }
    }

    override suspend fun markOpened(bookId: BookId) = registry.update(bookId) { it?.copy(lastOpenedAt = now()) }

    /**
     * The account may no longer download: what is still to fetch goes, with what was received of it, and the
     * updates on their way are taken back.
     */
    internal suspend fun cancelPending() {
        registry.books.first().forEach { book ->
            if (book.formats.any { it.isPending }) disposal.drop(book.bookId) { it.isPending }
        }
        updates.cancelPending()
    }
}

/** What is still to fetch of this format, its download or the update of what it keeps, may use any network. */
private fun FormatRecord.onAnyNetwork(): FormatRecord = when {
    isPending -> copy(anyNetwork = true)
    isUpdatePending -> copy(update = update?.copy(anyNetwork = true))
    else -> this
}

private val FormatRecord.isFailed: Boolean get() = !removing && state is FormatState.Failed
