package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.settings.PlayerDeviceSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.feature.player.PlayerSettingsActions
import app.tfdn.lirelle.feature.player.PlayerSettingsChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The player settings changed from the player screen or the settings screen, stored in [scope], which
 * outlives both, so that a change made just before leaving is kept. The service follows the store. They are
 * the device's settings, the same for every profile: the speed of a book is not changed from here.
 */
internal class SettingsChanges(private val store: PlayerDeviceSettings, private val scope: CoroutineScope) :
    PlayerSettingsActions,
    PlayerSettingsChanges {

    override fun setBackSeconds(seconds: Int) = change { it.copy(backSeconds = seconds) }

    override fun setForwardSeconds(seconds: Int) = change { it.copy(forwardSeconds = seconds) }

    override fun setRewindOnResume(enabled: Boolean) = change { it.copy(rewindOnResume = enabled) }

    override fun setSleepFade(enabled: Boolean) = change { it.copy(sleepFade = enabled) }

    override fun setShakeToExtend(enabled: Boolean) = change { it.copy(shakeToExtend = enabled) }

    private fun change(t: (PlayerSettings) -> PlayerSettings) {
        scope.launch { store.update(t) }
    }
}
