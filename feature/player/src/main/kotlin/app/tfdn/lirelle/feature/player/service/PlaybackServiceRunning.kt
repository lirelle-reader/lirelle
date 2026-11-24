package app.tfdn.lirelle.feature.player.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the playback service is running in this process, from its creation to its end. The shell's
 * mini-player watches the player only while it is: a service that does not run holds no book, and
 * connecting to it would only start it for nothing.
 *
 * One flag for the process, whatever profile the service plays for: enough for the screens, which are one
 * profile's at a time. What must know of one profile's service only asks that profile ([PlaybackShutdown]).
 */
object PlaybackServiceRunning {
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** Called by the service when it is created with a profile to play for, and when it ends. */
    fun set(running: Boolean) {
        mutableRunning.value = running
    }
}
