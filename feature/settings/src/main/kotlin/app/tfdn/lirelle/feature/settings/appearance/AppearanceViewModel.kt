package app.tfdn.lirelle.feature.settings.appearance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val StopWatchingAfterMillis = 5_000L

/** The app's theme as the Appearance page shows it, and the choice of another. */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: AppearanceStore,
    private val nightMode: NightMode,
) : ViewModel() {

    /** Null until the store is read: nothing is marked before. */
    val theme: StateFlow<AppTheme?> = store.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopWatchingAfterMillis), null)

    /**
     * Stores the theme, then gives it to the system. In that order: the system may draw the screen anew as
     * soon as it is told, and the screen shows what the store holds.
     *
     * A theme that could not be stored is not given to the system: the app would turn to it while the
     * store, the marked tile and the next start still hold the theme of before.
     */
    fun choose(theme: AppTheme) {
        viewModelScope.launch {
            if (store.setTheme(theme)) nightMode.apply(theme)
        }
    }
}
