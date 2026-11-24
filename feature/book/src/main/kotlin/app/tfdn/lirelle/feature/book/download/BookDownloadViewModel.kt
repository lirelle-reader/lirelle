package app.tfdn.lirelle.feature.book.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.engine.OfferedFormat
import app.tfdn.lirelle.core.downloads.engine.offeredFormats
import app.tfdn.lirelle.core.downloads.engine.textName
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The formats sheet while it is open.
 *
 * @property situation what its rows are made of; it follows the downloads.
 * @property freeBytes the space left on the device when it opened.
 * @property waitsForWifi the network is a metered one and the settings refuse it: a download will wait.
 */
data class FormatsSheetState(val situation: DownloadSituation, val freeBytes: Long, val waitsForWifi: Boolean)

/**
 * @property allowed whether the account may download: when not, the line under the tiles says so.
 * @property enabled whether a tap on the tile does something: there is a format to ask, or one to remove.
 * @property sheet null while the formats sheet is closed.
 * @property inUse a deletion was refused because the book is being read or played; said once.
 * @property askNotifications a download was just asked for the first time: the permission to show
 *   notifications is to be asked, if the system has not given it.
 * @property update where the update of a downloaded book is; null when it has none, and while one of its
 *   formats is still being downloaded.
 * @property keepsText the device has the text of the book whole: the reader opens that copy.
 */
data class BookDownloadUiState(
    val tile: TileState = TileState.NotDownloaded,
    val allowed: Boolean = true,
    val enabled: Boolean = false,
    val sheet: FormatsSheetState? = null,
    val inUse: Boolean = false,
    val askNotifications: Boolean = false,
    val update: UpdateState? = null,
    val keepsText: Boolean = false,
)

/** What the page itself decides: the rest of the state follows the downloads. */
private data class Local(
    val sheetOpen: Boolean = false,
    val freeBytes: Long = 0,
    val inUse: Boolean = false,
    val askNotifications: Boolean = false,
)

/** The downloads of the book of a page: its tile, the line under it and the formats sheet. */
@HiltViewModel(assistedFactory = BookDownloadViewModel.Factory::class)
class BookDownloadViewModel @AssistedInject constructor(
    @Assisted bookIdText: String,
    private val downloads: Downloads,
    private val removals: DownloadRemovals,
    private val updates: DownloadUpdates,
    private val surroundings: DownloadSurroundings,
    private val pages: KeptPages,
) : ViewModel() {

    /** The factory takes the id's text: an assisted parameter cannot be a value class. */
    @AssistedFactory
    interface Factory {
        fun create(bookIdText: String): BookDownloadViewModel
    }

    private val bookId = BookId(bookIdText)

    private val offered = MutableStateFlow(emptyList<OfferedFormat>())

    /** The book shown and where the server says its reading is: what a download asked here keeps at once. */
    private var shown: Pair<BookDetail, BookReading?>? = null
    private val local = MutableStateFlow(Local())

    private val situation = combine(
        offered,
        downloads.book(bookId),
        downloads.queue,
        surroundings.network,
        surroundings.canDownload,
        ::DownloadSituation,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSituation())

    val state: StateFlow<BookDownloadUiState> =
        combine(situation, surroundings.settings.settings, local) { situation, settings, local ->
            val metered = situation.network.online && !situation.network.unmetered
            BookDownloadUiState(
                tile = situation.tile(),
                allowed = situation.canDownload,
                enabled = situation.records.isNotEmpty() || (situation.canDownload && situation.offered.isNotEmpty()),
                sheet = FormatsSheetState(
                    situation = situation,
                    freeBytes = local.freeBytes,
                    waitsForWifi = situation.canDownload && metered && !settings.allowMetered,
                ).takeIf { local.sheetOpen },
                inUse = local.inUse,
                askNotifications = local.askNotifications,
                update = situation.update(),
                keepsText = situation.record(DownloadFormat.Text)?.isComplete == true,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BookDownloadUiState())

    /** The book the page shows, with its [reading] when the page has it: its formats are what can be asked. */
    fun show(book: BookDetail, reading: BookReading? = null) {
        shown = book to reading
        offered.value = book.offeredFormats()
    }

    /** A tap on the tile: the only format of a book that has nothing yet is asked at once; else the sheet. */
    fun onTile() {
        if (!state.value.enabled) return
        val only = situation.value.offered.singleOrNull()
        if (state.value.tile == TileState.NotDownloaded && only != null) {
            viewModelScope.launch { ask(setOf(only.format)) }
        } else {
            local.update { it.copy(sheetOpen = true, freeBytes = surroundings.freeBytes()) }
        }
    }

    /**
     * The action of the line under the tiles: cancel, download anyway or retry, as the tile stands; for a
     * downloaded book, what its update can have done.
     */
    fun onLineAction() {
        val tile = state.value.tile
        val update = state.value.update
        viewModelScope.launch {
            when (tile) {
                is TileState.Downloading, is TileState.Queued -> downloads.cancel(bookId)
                is TileState.WaitingForWifi -> downloads.allowAnyNetworkNow()
                is TileState.Failed -> downloads.retry(bookId)
                TileState.Downloaded -> act(update)
                else -> Unit
            }
        }
    }

    /** Update, cancel, download anyway or retry, as the [update] stands; the kept copy is left as it is. */
    private suspend fun act(update: UpdateState?) {
        when (update) {
            is UpdateState.Available -> updates.update(bookId)
            is UpdateState.Updating, is UpdateState.Queued -> downloads.cancel(bookId)
            is UpdateState.WaitingForWifi -> downloads.allowAnyNetworkNow()
            is UpdateState.Failed -> downloads.retry(bookId)
            UpdateState.Ready, null -> Unit
        }
    }

    fun closeSheet() = local.update { it.copy(sheetOpen = false) }

    /**
     * Does what the sheet was confirmed with, and closes it. What is whole is deleted, what is not is taken
     * out of the queue; when the book is in use nothing is done, and it is said.
     */
    fun apply(plan: SheetPlan) {
        val records = situation.value.records
        val (whole, pending) =
            plan.toDelete.partition { format -> records.any { it.format == format && it.isComplete } }
        closeSheet()
        viewModelScope.launch {
            if (whole.isNotEmpty() && removals.delete(bookId, whole.toSet()) == DeleteResult.InUse) {
                local.update { it.copy(inUse = true) }
                return@launch
            }
            pending.forEach { downloads.cancel(bookId, it) }
            if (plan.toDownload.isNotEmpty()) ask(plan.toDownload)
        }
    }

    fun inUseShown() = local.update { it.copy(inUse = false) }

    /** The page dealt with [BookDownloadUiState.askNotifications]; [asked] when it showed the system's dialog. */
    fun notificationsHandled(asked: Boolean) {
        local.update { it.copy(askNotifications = false) }
        if (asked) viewModelScope.launch { surroundings.settings.update { it.copy(notificationsAsked = true) } }
    }

    /**
     * The download starts whatever becomes of the notifications: they are asked for after it. The page of the
     * book is kept as soon as it is asked: a download that waits before its first byte has its title and its
     * cover. It is kept after the asking, which a cover slow to come must not hold back.
     */
    private suspend fun ask(formats: Set<DownloadFormat>) {
        // With the sizes the page announces: a download that waits before its first byte says what is left;
        // and with what its text is named by, which is then said before the server says what it is made of.
        val offered = situation.value.offered
        val sizes = offered
            .filter { it.format in formats }
            .mapNotNull { offer -> offer.estimatedBytes?.let { offer.format to it } }
            .toMap()
        if (downloads.ask(bookId, formats, sizes, offered.textName()) != null) return
        val asked = surroundings.settings.settings.first().notificationsAsked
        if (!asked) local.update { it.copy(askNotifications = true) }
        shown?.let { (book, reading) -> pages.keep(book, reading) }
    }
}
