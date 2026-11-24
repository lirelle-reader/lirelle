package app.tfdn.lirelle.feature.comics.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicDeviceSettings
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val StopWatchingAfterMillis = 5_000L

/** What the Comics page of the settings changes: the settings that belong to no series, the device's. */
interface ComicSettingsChanges {
    fun setFit(fit: Fit)

    fun setTwoPages(twoPages: TwoPages)

    fun setBackground(background: Background)

    /** Whether, panel by panel, each page is shown whole before its first panel. */
    fun setWholePageFirst(first: Boolean)
}

/**
 * The comics settings as the settings page shows them, read from and written to the store the comics reader
 * uses: a choice made here is the one the next album opens with. They are the device's settings, which no
 * profile holds: the reading direction is not among them, as it belongs to a series or to an album of a profile.
 */
@HiltViewModel
class ComicSettingsViewModel @Inject constructor(private val store: ComicDeviceSettings) :
    ViewModel(),
    ComicSettingsChanges {

    /** Null until the store is read: nothing is marked before. */
    val settings: StateFlow<ComicSettings?> = store.shared
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopWatchingAfterMillis), null)

    override fun setFit(fit: Fit) {
        viewModelScope.launch { store.setFit(fit) }
    }

    override fun setTwoPages(twoPages: TwoPages) {
        viewModelScope.launch { store.setTwoPages(twoPages) }
    }

    override fun setBackground(background: Background) {
        viewModelScope.launch { store.setBackground(background) }
    }

    override fun setWholePageFirst(first: Boolean) {
        viewModelScope.launch { store.setWholePageFirst(first) }
    }
}
