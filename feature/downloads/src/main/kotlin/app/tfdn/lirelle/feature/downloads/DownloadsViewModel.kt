package app.tfdn.lirelle.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the state stays collected after the screen left: a rotation does not read everything again. */
private const val KeepMillis = 5_000L

/** What the screen itself asked to see over the lists. */
private data class Overlay(
    val snackbar: DownloadsSnackbar? = null,
    val deleteSheet: DeleteSheetUi? = null,
    val serial: Int = 0,
)

/** A new message, in the place of the one there may be. */
private fun MutableStateFlow<Overlay>.show(kind: DownloadsSnackbar.Kind, bookId: BookId? = null) =
    update { it.copy(snackbar = DownloadsSnackbar(kind, it.serial + 1, bookId), serial = it.serial + 1) }

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: Downloads,
    private val removals: DownloadRemovals,
    private val settings: DownloadSettingsStore,
    private val sources: DownloadsSources,
    updates: DownloadUpdates,
) : ViewModel() {

    /** What the screen asks of the updates. */
    val updating = UpdateRequests(viewModelScope, updates)

    private val keptBooks = KeptBooks(sources)

    /** Counts the times the screen was shown: the progress of the books is read again at each. */
    private val shown = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    val state: StateFlow<DownloadsUiState> =
        combine(downloads.books, downloads.queue, shown, overlay, updating.refreshing) {
                books, queue, epoch, overlay, refreshing ->
            downloadsUiState(books, queue, keptBooks.of(books, epoch), sources.storage())
                .copy(snackbar = overlay.snackbar, deleteSheet = overlay.deleteSheet, refreshing = refreshing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(KeepMillis), DownloadsUiState())

    /** The user may have read or listened since the last time. */
    fun onShown() = shown.update { it + 1 }

    fun cancel(bookId: BookId) {
        viewModelScope.launch { downloads.cancel(bookId) }
    }

    fun retry(bookId: BookId) {
        viewModelScope.launch { downloads.retry(bookId) }
    }

    /** The downloads now waiting for Wi-Fi go on the network there is. */
    fun downloadNow() {
        viewModelScope.launch { downloads.allowAnyNetworkNow() }
    }

    /** Lifts the Wi-Fi rule for good; the message that says so offers to set it back. */
    fun alwaysAllowMobile() {
        viewModelScope.launch {
            settings.update { it.copy(allowMetered = true) }
            overlay.show(DownloadsSnackbar.Kind.MobileAllowed)
        }
    }

    /** Deletes at once a book that keeps one format; asks which ones of a book that keeps several. */
    fun askDelete(bookId: BookId) {
        viewModelScope.launch {
            val options = downloads.book(bookId).first()?.complete().orEmpty()
            when (options.size) {
                0 -> Unit
                1 -> delete(bookId, setOf(options.single().format))
                else -> overlay.update {
                    val title = sources.kept.detail(bookId)?.title.orEmpty()
                    it.copy(deleteSheet = DeleteSheetUi(bookId, title, options.sumOf(DeleteOption::bytes), options))
                }
            }
        }
    }

    /** Deletes [formats] of the book of the sheet. */
    fun confirmDelete(formats: Set<DownloadFormat>) {
        val bookId = overlay.value.deleteSheet?.bookId ?: return
        dismissDelete()
        viewModelScope.launch { delete(bookId, formats) }
    }

    fun dismissDelete() = overlay.update { it.copy(deleteSheet = null) }

    /** [snackbar] left the screen, by its "Undo" or not. */
    fun snackbarDone(snackbar: DownloadsSnackbar, undo: Boolean) {
        overlay.update { if (it.snackbar == snackbar) it.copy(snackbar = null) else it }
        if (!undo) return
        viewModelScope.launch {
            when (snackbar.kind) {
                DownloadsSnackbar.Kind.MobileAllowed -> settings.update { it.copy(allowMetered = false) }
                DownloadsSnackbar.Kind.Deleted -> snackbar.bookId?.let { removals.undoDelete(it) }
                DownloadsSnackbar.Kind.InUse -> Unit
            }
        }
    }

    private suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>) = when (removals.delete(bookId, formats)) {
        DeleteResult.Deleted -> overlay.show(DownloadsSnackbar.Kind.Deleted, bookId)
        DeleteResult.InUse -> overlay.show(DownloadsSnackbar.Kind.InUse)
    }
}
