package app.tfdn.lirelle.core.library.di

import app.tfdn.lirelle.core.library.net.DeviceNetwork
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.DeviceSignals
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.CoroutineScope

/**
 * The network of one profile: the only place where a profile's objects meet the device's network, so that
 * none of them goes on asking a server that no longer takes the profile's session.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ProfileNetworkModule {

    @Provides
    @ProfileScoped
    fun profileNetwork(
        device: DeviceNetwork,
        signals: DeviceSignals,
        session: ProfileSession,
        @ProfileLife life: CoroutineScope,
    ): ProfileNetwork = ProfileNetwork(device.state, signals.networkAvailable, session.signInNeeded, life)
}

/** The open profile's network, for what Hilt cannot inject it into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface NetworkProfileEntry {
    fun profileNetwork(): ProfileNetwork
}

/**
 * Gives view models the network of the open profile. Unscoped, and in no component that outlives a profile:
 * each view model gets that of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ProfileNetworkBridge {

    @Provides
    fun network(current: CurrentProfile): ProfileNetwork = current.entry<NetworkProfileEntry>().profileNetwork()
}
