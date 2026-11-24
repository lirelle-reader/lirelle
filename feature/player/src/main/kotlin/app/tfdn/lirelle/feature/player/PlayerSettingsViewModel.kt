package app.tfdn.lirelle.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.audio.settings.PlayerDeviceSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.feature.player.controller.PlayerControllerScope
import app.tfdn.lirelle.feature.player.controller.SettingsChanges
import app.tfdn.lirelle.feature.player.controller.StopWatchingAfter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** What the player settings screen changes. */
interface PlayerSettingsChanges {
    fun setBackSeconds(seconds: Int)

    fun setForwardSeconds(seconds: Int)

    fun setRewindOnResume(enabled: Boolean)

    fun setSleepFade(enabled: Boolean)

    fun setShakeToExtend(enabled: Boolean)
}

/**
 * The player settings screen: what is stored, and each change stored in [scope], which outlives the
 * screen, as the player screen's own changes are ([SettingsChanges]). It shows and changes the device's
 * settings only, which no profile holds: the speeds of the books are not its business.
 */
@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    store: PlayerDeviceSettings,
    @PlayerControllerScope scope: CoroutineScope,
) : ViewModel(), PlayerSettingsChanges by SettingsChanges(store, scope) {

    /** Null until the store was read. */
    val settings: StateFlow<PlayerSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds), null)
}
