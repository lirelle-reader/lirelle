package app.tfdn.lirelle.core.library.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @property online the device has a network that reaches the internet.
 * @property unmetered that network is not a metered one.
 */
data class NetworkState(val online: Boolean, val unmetered: Boolean)

/**
 * The app's one network observer: whether the device has a network, and an unmetered one, from the system's
 * default network. It watches for as long as the process lives.
 */
@Singleton
class DeviceNetwork @Inject constructor(@ApplicationContext context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableState =
        MutableStateFlow(stateOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
    private val mutableOnline = MutableStateFlow(mutableState.value.online)

    val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    /** [state]'s `online`, on its own. */
    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()

    /** [state]'s `unmetered`, as it is now. */
    val unmetered: Boolean get() = mutableState.value.unmetered

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            tell(stateOf(capabilities))

        override fun onLost(network: Network) = tell(NetworkState(online = false, unmetered = false))
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun tell(state: NetworkState) {
        mutableState.value = state
        mutableOnline.value = state.online
    }

    private companion object {
        fun stateOf(capabilities: NetworkCapabilities?) = NetworkState(
            online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
        )
    }
}
