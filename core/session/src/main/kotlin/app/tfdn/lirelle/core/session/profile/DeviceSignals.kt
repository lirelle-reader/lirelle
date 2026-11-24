package app.tfdn.lirelle.core.session.profile

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the device tells whoever is open: the network came back, the app is in front or not. The application
 * feeds it for as long as the process lives and knows nothing of the profiles; what starts with a profile
 * follows it for as long as that profile lives.
 */
@Singleton
class DeviceSignals @Inject constructor() {

    private val mutableNetworkAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Each time the device has a network again. Not replayed: who starts later asks by itself at its start. */
    val networkAvailable: SharedFlow<Unit> = mutableNetworkAvailable.asSharedFlow()

    private val mutableInFront = MutableStateFlow(false)

    /** Whether the app is on screen. */
    val inFront: StateFlow<Boolean> = mutableInFront.asStateFlow()

    /**
     * How many follow it at this moment. Every one of them belongs to a profile that is open: what a closed
     * profile left behind shows here, and would go on acting for an account that is no longer on screen.
     */
    val followers: Int
        get() = mutableNetworkAvailable.subscriptionCount.value + mutableInFront.subscriptionCount.value

    /** Never waits for a follower: two that come at once are, for it, one. */
    fun onNetworkAvailable() {
        mutableNetworkAvailable.tryEmit(Unit)
    }

    fun onShown() {
        mutableInFront.value = true
    }

    fun onHidden() {
        mutableInFront.value = false
    }
}
