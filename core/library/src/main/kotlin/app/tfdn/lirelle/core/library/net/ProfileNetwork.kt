package app.tfdn.lirelle.core.library.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * The network as one open profile may use it: the device's, and none at all while the profile's session waits
 * for a sign-in. Its server would refuse everything then, so the profile behaves as it does without a network
 * — what is kept on the device opens, what is written waits, nothing is fetched — and starts again by itself
 * when the same account has signed in, as it does when the network comes back.
 *
 * What belongs to a profile asks this, never the device ([DeviceNetwork]): the device knows nothing of
 * sessions.
 *
 * @param device the device's network, as it changes.
 * @param deviceReturns each time the device has a network again.
 * @param signInNeeded whether the profile's session waits for a sign-in.
 * @param life the profile's: [state] and [online] are followed for as long as it lasts.
 */
class ProfileNetwork(
    private val device: StateFlow<NetworkState>,
    deviceReturns: Flow<Unit>,
    private val signInNeeded: StateFlow<Boolean>,
    life: CoroutineScope,
) {
    /** [state]'s `online` at this moment, asked where it is known: no change is still on its way to it. */
    val isOnline: Boolean get() = device.value.online && !signInNeeded.value

    /**
     * Whether it is the session, not the device, that keeps the profile from its server. For who asks the
     * system to wake it when a network is there: the system knows the device's network only, and would wake
     * it for nothing for as long as this lasts.
     */
    val waitsForSignIn: StateFlow<Boolean> get() = signInNeeded

    /**
     * Whether the device itself has a network at this moment, whatever the session. For who must tell a
     * profile kept from its server by the device from one kept from it by its session: [isOnline] is false
     * for both.
     */
    val deviceOnline: Boolean get() = device.value.online

    /** The device's network is not a metered one, as it is now. */
    val unmetered: Boolean get() = device.value.unmetered

    /** The device's network; not [NetworkState.online] while the session waits for a sign-in. */
    val state: StateFlow<NetworkState> = combine(device, signInNeeded, ::usable)
        .stateIn(life, SharingStarted.Eagerly, usable(device.value, signInNeeded.value))

    /** [state]'s `online`, on its own. */
    val online: StateFlow<Boolean> = state.map { it.online }.stateIn(life, SharingStarted.Eagerly, state.value.online)

    /**
     * Each time the profile may reach its server again: the device has a network again while the session is
     * good, or somebody signed in again while the device has one. Not replayed: who starts later asks by
     * itself at its start.
     */
    val returns: Flow<Unit> = merge(
        deviceReturns.filter { !signInNeeded.value },
        signInNeeded.drop(1).filter { needed -> !needed && device.value.online }.map { },
    )

    private companion object {
        fun usable(device: NetworkState, signInNeeded: Boolean) = device.copy(online = device.online && !signInNeeded)
    }
}
