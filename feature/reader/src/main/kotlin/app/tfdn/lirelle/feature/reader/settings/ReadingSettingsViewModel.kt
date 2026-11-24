package app.tfdn.lirelle.feature.reader.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val StopWatchingAfterMillis = 5_000L

/**
 * The reading settings as the Reading page of the settings shows them, and their changes. They are the ones
 * the reader uses: a change made here holds in the reader at once, and the reverse.
 */
@HiltViewModel
class ReadingSettingsViewModel @Inject constructor(
    private val store: ReaderSettingsStore,
    capabilities: SourceCapabilities,
) : ViewModel() {

    /** False for a source without annotations: the page has no corner's bookmark (spec 2c). */
    val cornerBookmark: Boolean = capabilities.annotations

    /** Null until the store is read: the page shows no default for a moment. */
    val settings: StateFlow<ReaderSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopWatchingAfterMillis), null)

    /** Changes the settings: the transform is applied to what is stored when it is handled. */
    fun change(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { store.update(transform) }
    }
}
