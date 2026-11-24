package app.tfdn.lirelle.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val StopAfterMillis = 5_000L

/**
 * The Downloads section of More.
 *
 * @property allowMetered whether downloads may use a metered network.
 * @property books the books kept or being fetched.
 * @property usedBytes what they hold on the device.
 * @property freeBytes the room left where the downloads go.
 */
data class DownloadsSettingsState(
    val allowMetered: Boolean = false,
    val books: Int = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
)

/**
 * @param freeBytes the room left where the downloads go, asked on [io] each time what is kept changes.
 */
@HiltViewModel
class DownloadsSettingsViewModel internal constructor(
    downloads: Downloads,
    private val removals: DownloadRemovals,
    private val settings: DownloadSettingsStore,
    freeBytes: () -> Long,
    io: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        downloads: Downloads,
        removals: DownloadRemovals,
        settings: DownloadSettingsStore,
        files: DownloadFiles,
    ) : this(downloads, removals, settings, files::freeBytes, Dispatchers.IO)

    val state: StateFlow<DownloadsSettingsState> =
        combine(settings.settings, downloads.books) { settings, books ->
            DownloadsSettingsState(settings.allowMetered, books.size, books.sumOf { it.bytesOnDevice }, freeBytes())
        }
            .flowOn(io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopAfterMillis), DownloadsSettingsState())

    fun onAllowMetered(allowed: Boolean) {
        viewModelScope.launch { settings.update { it.copy(allowMetered = allowed) } }
    }

    /** Everything, at once: the screen asked first. */
    fun onDeleteAll() {
        viewModelScope.launch { removals.deleteAll() }
    }
}
