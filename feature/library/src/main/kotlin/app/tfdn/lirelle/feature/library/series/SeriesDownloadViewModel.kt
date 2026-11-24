package app.tfdn.lirelle.feature.library.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.engine.offeredFormats
import app.tfdn.lirelle.core.downloads.engine.textName
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How many pages of books are read from the server at the same time. */
private const val PagesAtOnce = 4

/**
 * What the device has of a series.
 *
 * @property books those with at least one format whole; never zero.
 * @property of the books of the series.
 * @property bytes what those books take on the device.
 */
data class SeriesOnDevice(val books: Int, val of: Int, val bytes: Long)

/**
 * The sheet of a series while it is open.
 *
 * @property name the series'.
 * @property books how many it has, [onDevice] of them on the device already.
 * @property offers what each book offers and keeps, in the order of the series, once its page is read.
 * @property chosen the formats proposed when it opens: the last choice made in this sheet.
 * @property freeBytes the space left on the device when it opened.
 * @property waitsForWifi the network is a metered one and the settings refuse it: the downloads will wait.
 */
data class SeriesSheetState(
    val name: String,
    val books: Int,
    val onDevice: Int,
    val offers: Loadable<List<SeriesBookOffer>>,
    val chosen: Set<DownloadFormat>,
    val freeBytes: Long,
    val waitsForWifi: Boolean,
)

/**
 * @property allowed whether the account may download: when not, the button is disabled and its line says so.
 * @property onDevice null while nothing of the series is on the device.
 * @property sheet null while the sheet is closed.
 * @property askNotifications a download was just asked for the first time: the permission to show
 *   notifications is to be asked, if the system has not given it.
 */
data class SeriesDownloadUiState(
    val allowed: Boolean = true,
    val onDevice: SeriesOnDevice? = null,
    val sheet: SeriesSheetState? = null,
    val askNotifications: Boolean = false,
)

/** What the page itself decides: the rest of the state follows the downloads. */
private data class SeriesLocal(
    val series: SeriesBooks? = null,
    val sheetOpen: Boolean = false,
    val details: Loadable<List<BookDetail>> = Loadable.Loading,
    val freeBytes: Long = 0,
    val askNotifications: Boolean = false,
)

/** The download of a whole series from its page: the button, its line and the sheet. */
@HiltViewModel
class SeriesDownloadViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val downloads: Downloads,
    private val surroundings: DownloadSurroundings,
    private val kept: KeptBookData,
) : ViewModel() {

    private val local = MutableStateFlow(SeriesLocal())
    private var loading: Job? = null

    val state: StateFlow<SeriesDownloadUiState> = combine(
        local,
        downloads.books,
        surroundings.network,
        surroundings.canDownload,
        surroundings.settings.settings,
    ) { local, books, network, canDownload, settings ->
        val records = books.associateBy { it.bookId }
        val onDevice = local.series?.onDevice(records)
        SeriesDownloadUiState(
            allowed = canDownload,
            onDevice = onDevice,
            sheet = local.series?.takeIf { local.sheetOpen }?.let { series ->
                SeriesSheetState(
                    name = series.series.name,
                    books = series.books.size,
                    onDevice = onDevice?.books ?: 0,
                    offers = local.details.offers(series, records),
                    chosen = settings.seriesFormats,
                    freeBytes = local.freeBytes,
                    waitsForWifi = canDownload && network.online && !network.unmetered && !settings.allowMetered,
                )
            },
            askNotifications = local.askNotifications,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeriesDownloadUiState())

    /** The series the page shows: its books, in their order, are what the sheet offers. */
    fun show(series: SeriesBooks) = local.update { it.copy(series = series) }

    /** A tap on the button: the sheet opens and reads the page of each book. */
    fun onButton() {
        if (!state.value.allowed || local.value.series == null) return
        local.update { it.copy(sheetOpen = true, freeBytes = surroundings.freeBytes()) }
        load()
    }

    /** Reads the pages again, after one could not be read. */
    fun retry() = load()

    fun closeSheet() {
        loading?.cancel()
        local.update { it.copy(sheetOpen = false) }
    }

    /**
     * Asks the queue, book by book in the order of the series, for the formats [chosen] that the book offers and
     * does not keep; remembers the choice; closes the sheet. The page of each book asked is kept at once, after
     * the asking, which a cover slow to come must not hold back: a download that waits before its first byte
     * has its title and its cover.
     */
    fun confirm(chosen: Set<DownloadFormat>, skipFinished: Boolean) {
        val offers = (state.value.sheet?.offers as? Loadable.Ready)?.value ?: return
        val details = (local.value.details as? Loadable.Ready)?.value.orEmpty().associateBy { it.id }
        val plan = seriesPlan(offers, chosen, skipFinished)
        closeSheet()
        viewModelScope.launch {
            surroundings.settings.update { it.copy(seriesFormats = chosen) }
            val asked = plan.asks.filter { (bookId, formats) ->
                val offered = offers.first { it.bookId == bookId }.offered
                val sizes = offered
                    .filter { it.format in formats }
                    .mapNotNull { offer -> offer.estimatedBytes?.let { offer.format to it } }
                    .toMap()
                // Each book with the name of its own text: a comic that waits is not shown as a text.
                downloads.ask(bookId, formats, sizes, offered.textName()) == null
            }
            if (asked.isEmpty()) return@launch
            if (!surroundings.settings.settings.first().notificationsAsked) {
                local.update { it.copy(askNotifications = true) }
            }
            asked.forEach { (bookId, _) -> details[bookId]?.let { kept.keep(it) } }
        }
    }

    /** The page dealt with [SeriesDownloadUiState.askNotifications]; [asked] when it showed the system's dialog. */
    fun notificationsHandled(asked: Boolean) {
        local.update { it.copy(askNotifications = false) }
        if (asked) viewModelScope.launch { surroundings.settings.update { it.copy(notificationsAsked = true) } }
    }

    /** The page of each book of the series, a few at a time; one that cannot be read fails them all. */
    private fun load() {
        val books = local.value.series?.books ?: return
        loading?.cancel()
        loading = viewModelScope.launch {
            local.update { it.copy(details = Loadable.Loading) }
            val gate = Semaphore(PagesAtOnce)
            val results = coroutineScope {
                books.map { book -> async { gate.withPermit { repository.book(book.id) } } }.awaitAll()
            }
            val failure = results.firstNotNullOfOrNull { it as? ApiResult.Failure }
            val details = failure?.let { Loadable.Failed(it.error) }
                ?: Loadable.Ready(results.mapNotNull { (it as? ApiResult.Success)?.value })
            local.update { it.copy(details = details) }
        }
    }
}

private fun SeriesBooks.onDevice(records: Map<BookId, DownloadedBook>): SeriesOnDevice? {
    val downloaded = books.mapNotNull { records[it.id] }.filter { it.isDownloaded }
    if (downloaded.isEmpty()) return null
    return SeriesOnDevice(downloaded.size, books.size, downloaded.sumOf { it.bytesOnDevice })
}

/**
 * The formats the device keeps or is fetching: whole, queued, on their way or waiting. One that failed is not
 * kept: it is offered, and asking for it queues it again.
 */
private fun DownloadedBook?.keptFormats(): Set<DownloadFormat> = this?.formats.orEmpty()
    .filterNot { it.removing || it.state is FormatState.Failed }
    .mapTo(mutableSetOf()) { it.format }

/** What each book offers, whether it is finished as the series lists it, and what the device keeps of it. */
private fun Loadable<List<BookDetail>>.offers(
    series: SeriesBooks,
    records: Map<BookId, DownloadedBook>,
): Loadable<List<SeriesBookOffer>> = when (this) {
    Loadable.Loading -> Loadable.Loading
    is Loadable.Failed -> this
    is Loadable.Ready -> {
        val finished = series.books.filter { it.isRead }.mapTo(mutableSetOf()) { it.id }
        Loadable.Ready(
            value.map { detail ->
                SeriesBookOffer(
                    bookId = detail.id,
                    finished = detail.id in finished,
                    offered = detail.offeredFormats(),
                    kept = records[detail.id].keptFormats(),
                )
            },
        )
    }
}
